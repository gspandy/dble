package io.mycat.plan.common.item.function;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import org.apache.commons.lang.StringUtils;

import com.alibaba.druid.sql.ast.SQLExpr;

import io.mycat.config.ErrorCode;
import io.mycat.plan.PlanNode;
import io.mycat.plan.PlanNode.PlanNodeType;
import io.mycat.plan.common.context.NameResolutionContext;
import io.mycat.plan.common.context.ReferContext;
import io.mycat.plan.common.exception.MySQLOutPutException;
import io.mycat.plan.common.field.Field;
import io.mycat.plan.common.item.FieldTypes;
import io.mycat.plan.common.item.Item;
import io.mycat.plan.common.time.MySQLTime;
import io.mycat.plan.node.JoinNode;

public abstract class ItemFunc extends Item {

	public enum Functype {
		UNKNOWN_FUNC, EQ_FUNC, EQUAL_FUNC, NE_FUNC, LT_FUNC, LE_FUNC, GE_FUNC, GT_FUNC, FT_FUNC, LIKE_FUNC, ISNULL_FUNC, ISNOTNULL_FUNC, COND_AND_FUNC, COND_OR_FUNC, XOR_FUNC, BETWEEN, IN_FUNC, MULT_EQUAL_FUNC, INTERVAL_FUNC, ISNOTNULLTEST_FUNC, SP_EQUALS_FUNC, SP_DISJOINT_FUNC, SP_INTERSECTS_FUNC, SP_TOUCHES_FUNC, SP_CROSSES_FUNC, SP_WITHIN_FUNC, SP_CONTAINS_FUNC, SP_OVERLAPS_FUNC, SP_STARTPOINT, SP_ENDPOINT, SP_EXTERIORRING, SP_POINTN, SP_GEOMETRYN, SP_INTERIORRINGN, NOT_FUNC, NOT_ALL_FUNC, NOW_FUNC, TRIG_COND_FUNC, SUSERVAR_FUNC, GUSERVAR_FUNC, COLLATE_FUNC, EXTRACT_FUNC, CHAR_TYPECAST_FUNC, FUNC_SP, UDF_FUNC, NEG_FUNC, GSYSVAR_FUNC
	};

	protected final List<Item> args;

	public ItemFunc(List<Item> args) {
		this.args = args;
	}

	@Override
	public ItemType type() {
		return ItemType.FUNC_ITEM;
	}

	public final int getArgCount() {
		if (args == null)
			return 0;
		return args.size();
	}

	public final List<Item> arguments() {
		return args;
	}

	public Functype functype() {
		return Functype.UNKNOWN_FUNC;
	}

	@Override
	public int hashCode() {
		int prime = 31;
		int hashCode = funcName().hashCode();
		hashCode = hashCode * prime;
		for (int index = 0; index < getArgCount(); index++) {
			hashCode += args.get(index).hashCode();
		}
		return hashCode;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (!(obj instanceof ItemFunc))
			return false;
		ItemFunc other = (ItemFunc) obj;
		if (!funcName().equals(other.funcName()))
			return false;
		if (getArgCount() != other.getArgCount())
			return false;
		return StringUtils.equals(getItemName(), other.getItemName());
	}

	@Override
	public boolean fixFields() {
		if (!fixed) {
			if (args != null && args.size() > 0) {
				for (Item arg : args) {
					if ((!arg.fixed && arg.fixFields()))
						return true; /* purecov: inspected */
					if (arg.maybeNull)
						maybeNull = true;
					withSumFunc = withSumFunc || arg.withSumFunc;
					withIsNull = withIsNull || arg.withIsNull;
					withSubQuery = withSubQuery || arg.withSubQuery;
					withUnValAble = withUnValAble || arg.withUnValAble;
				}
			}
			fixLengthAndDec();
		}
		fixed = true;
		return false;
	}

	@Override
	public boolean isNull() {
		updateNullValue();
		return nullValue;
	}

	public void signalDivideByNull() {
		logger.warn("divide by zero");
		nullValue = true;
	}

	@Override
	public BigDecimal valDecimal() {
		BigInteger nr = valInt();
		if (nullValue)
			return null;
		return new BigDecimal(nr);
	}

	public boolean hasTimestampArgs() {
		assert (fixed == true);
		if (args != null && args.size() > 0)
			for (Item arg : args) {
				if (arg.type() == ItemType.FIELD_ITEM && arg.fieldType() == FieldTypes.MYSQL_TYPE_TIMESTAMP)
					return true;
			}
		return false;
	}

	public boolean hasDateArgs() {
		assert (fixed == true);
		if (args != null && args.size() > 0)
			for (Item arg : args) {
				if (arg.type() == ItemType.FIELD_ITEM && (arg.fieldType() == FieldTypes.MYSQL_TYPE_DATE
						|| arg.fieldType() == FieldTypes.MYSQL_TYPE_DATETIME))
					return true;
			}
		return false;
	}

	public boolean hasTimeArgs() {
		assert (fixed == true);
		if (args != null && args.size() > 0)
			for (Item arg : args) {
				if (arg.type() == ItemType.FIELD_ITEM && (arg.fieldType() == FieldTypes.MYSQL_TYPE_TIME
						|| arg.fieldType() == FieldTypes.MYSQL_TYPE_DATETIME))
					return true;
			}
		return false;
	}

	public boolean hasDatetimeArgs() {
		assert (fixed == true);
		if (args != null && args.size() > 0)
			for (Item arg : args) {
				if (arg.type() == ItemType.FIELD_ITEM && arg.fieldType() == FieldTypes.MYSQL_TYPE_DATETIME)
					return true;
			}
		return false;
	}

	public abstract void fixLengthAndDec();

	public ItemFunc nativeConstruct(List<Item> realArgs) {
		throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "", "no native function called!");
	}

	public abstract String funcName();

	/**
	 * Set max_length/decimals of function if function is floating point and
	 * result length/precision depends on argument ones.
	 */
	public void countRealLength() {
		int length = 0;
		decimals = 0;
		maxLength = 0;
		for (int i = 0; i < args.size(); i++) {
			if (decimals != NOT_FIXED_DEC) {
				decimals = Math.max(decimals, args.get(i).decimals);
				length = Math.max(length, args.get(i).maxLength - args.get(i).decimals);
			}
			maxLength = Math.max(maxLength, args.get(i).maxLength);
		}
		if (decimals != NOT_FIXED_DEC) {
			maxLength = length;
			length += decimals;
			if (length < maxLength) // If previous operation gave overflow
				maxLength = Integer.MAX_VALUE;
			else
				maxLength = length;
		}
	}

	/**
	 * Set max_length/decimals of function if function is fixed point and result
	 * length/precision depends on argument ones.
	 */

	public void countDecimalLength() {
		int maxIntPart = 0;
		decimals = 0;
		for (int i = 0; i < args.size(); i++) {
			decimals = Math.max(decimals, args.get(i).decimals);
			maxIntPart = Math.max(maxIntPart, args.get(i).decimalIntPart());
		}
		int precision = maxIntPart + decimals;
		maxLength = precision;
	}

	/**
	 * Calculate max_length and decimals for STRING_RESULT functions.
	 * 
	 * @param field_type
	 *            Field type.
	 * @param items
	 *            Argument array.
	 * @param nitems
	 *            Number of arguments.
	 * @retval False on success, true on error.
	 */
	public boolean countStringResultLength() {
		return false;
	}

	public boolean getArg0Date(MySQLTime ltime, long fuzzy_date) {
		return (nullValue = args.get(0).getDate(ltime, fuzzy_date));
	}

	public boolean getArg0Time(MySQLTime ltime) {
		return (nullValue = args.get(0).getTime(ltime));
	}

	@Override
	public final ItemFunc fixFields(NameResolutionContext context) {
		getReferTables().clear();
		if (getArgCount() > 0) {
			for (int index = 0; index < getArgCount(); index++) {
				Item arg = args.get(index);
				Item fixedArg = arg.fixFields(context);
				if (fixedArg == null)
					return null;
				args.set(index, fixedArg);
				getReferTables().addAll(fixedArg.getReferTables());
				withSumFunc = withSumFunc || fixedArg.withSumFunc;
				withIsNull = withIsNull || fixedArg.withIsNull;
				withSubQuery = withSubQuery || fixedArg.withSubQuery;
				withUnValAble = withUnValAble || arg.withUnValAble;
			}
		}
		return this;
	}

	@Override
	public final void fixRefer(ReferContext context) {
		PlanNode planNode = context.getPlanNode();
		if (withSumFunc) {
			planNode.addSelToReferedMap(planNode, this);
			for (Item arg : args) {
				arg.fixRefer(context);
			}
		} else if (getReferTables().isEmpty()) {
			if (planNode.type() == PlanNodeType.TABLE) {
				planNode.addSelToReferedMap(planNode, this);
			} else {
				planNode.addSelToReferedMap(planNode.getChild(), this);
			}
		} else {
			if (getReferTables().size() == 1) {
				PlanNode pn = getReferTables().iterator().next();
				boolean existUnpushIsNull = false;
				if (withIsNull && planNode.type() == PlanNodeType.JOIN) {
					JoinNode jn = (JoinNode) planNode;
					if (jn.isLeftOuterJoin() && jn.getRightNode() == pn)
						existUnpushIsNull = true;
				}
				if (!existUnpushIsNull)
					planNode.addSelToReferedMap(pn, this);
				else {
					planNode.addSelToReferedMap(planNode, this);
					for (Item arg : args) {
						arg.fixRefer(context);
					}
				}
			} else {
				planNode.addSelToReferedMap(planNode, this);
				if (!context.isPushDownNode()) {
					for (Item arg : args) {
						arg.fixRefer(context);
					}
				}
			}
		}
	}

	@Override
	public SQLExpr toExpression() {
//		if (ItemCreate.getInstance().isNativeFunc(this.funcName())) {
//			List<SQLExpr> exprList = toExpressionList(args);
//			FunctionExpression nativeFe = MySQLFunctionManager.INSTANCE_MYSQL_DEFAULT
//					.createFunctionExpression(this.funcName().toUpperCase(), exprList);
//			return nativeFe;
//		} else {
//			throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "", "unexpected function:" + funcName());
//		}
		throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "", "unexpected function:" + funcName());
	}

	@Override
	protected Item cloneStruct(boolean forCalculate, List<Item> calArgs, boolean isPushDown, List<Field> fields) {
		if (!forCalculate) {
			if (ItemCreate.getInstance().isNativeFunc(this.funcName())) {
				List<Item> argList = cloneStructList(this.args);
				return ItemCreate.getInstance().createNativeFunc(funcName(), argList);
			} else {
				throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "", "unexpected function:" + funcName());
			}
		} else {
			if (ItemCreate.getInstance().isNativeFunc(this.funcName())) {
				return ItemCreate.getInstance().createNativeFunc(funcName(), calArgs);
			} else {
				throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "", "unexpected function:" + funcName());
			}
		}
	}

}