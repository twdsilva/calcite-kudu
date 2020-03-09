package com.twilio.raas.sql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.JoinType;
import org.apache.calcite.linq4j.function.Function2;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.TimestampString;
import org.apache.kudu.Schema;
import org.apache.kudu.client.KuduPredicate;
import org.apache.kudu.client.KuduPredicate.ComparisonOp;

public class TranslationPredicate {
    private final int leftKuduIndex;
    private final int rightKuduIndex;
    private final ComparisonOp operation;
    private final Schema tableSchema;

    public TranslationPredicate(final int leftOrdinal, final int rightOrdinal, final RexCall functionCall,
            final Schema tableSchema) {
        this.leftKuduIndex = leftOrdinal;
        this.rightKuduIndex = rightOrdinal;
        this.tableSchema = tableSchema;

        switch (functionCall.getOperator().getKind()) {
            case EQUALS:
                this.operation = ComparisonOp.EQUAL;
                break;
            case GREATER_THAN:
                this.operation = ComparisonOp.GREATER;
                break;
            case GREATER_THAN_OR_EQUAL:
                this.operation = ComparisonOp.GREATER_EQUAL;
                break;
            case LESS_THAN:
                this.operation = ComparisonOp.LESS;
                break;
            case LESS_THAN_OR_EQUAL:
                this.operation = ComparisonOp.LESS_EQUAL;
            default:
                throw new IllegalArgumentException(
                        String.format("TranslationPredicate is unable to handle this call type: %s",
                                functionCall.getOperator().getKind()));
        }
    }

    public CalciteKuduPredicate toPredicate(final Object[] leftRow) {
        return new CalciteKuduPredicate(
            rightKuduIndex,
            operation,
            leftRow[leftKuduIndex]);
    }

    /**
     * Computes the conjunction based on the join condition
     */
    public static class ConditionTranslationVisitor extends RexVisitorImpl<List<TranslationPredicate>> {
        private final int leftSize;
        private final Schema tableSchema;
        private final Project rightSideProjection;

        public ConditionTranslationVisitor(final int leftSize, final Project rightSideProjection,
            final Schema tableSchema) {

            super(true);
            this.leftSize = leftSize;
            this.tableSchema = tableSchema;
            this.rightSideProjection = rightSideProjection;
        }

        public List<TranslationPredicate> visitCall(RexCall call) {
            final SqlKind callType = call.getOperator().getKind();

            switch (callType) {
                case EQUALS:
                case GREATER_THAN:
                case GREATER_THAN_OR_EQUAL:
                case LESS_THAN:
                case LESS_THAN_OR_EQUAL:
                    if (call.operands.get(0) instanceof RexInputRef && call.operands.get(1) instanceof RexInputRef) {
                        final RexInputRef left;
                        final RexInputRef right;
                        if (((RexInputRef) call.operands.get(0)).getIndex() < ((RexInputRef) call.operands.get(1))
                                .getIndex()) {
                            left = (RexInputRef) call.operands.get(0);
                            right = (RexInputRef) call.operands.get(1);
                        } else {
                            left = (RexInputRef) call.operands.get(1);
                            right = (RexInputRef) call.operands.get(0);

                        }

                        final int rightPositionInEnumerable = right.getIndex() - leftSize;

                        final int rightIndex;

                        if (rightSideProjection != null &&
                            rightSideProjection.getChildExps()
                            .get(rightPositionInEnumerable)
                            instanceof RexInputRef) {
                            rightIndex = ((RexInputRef)rightSideProjection
                                .getChildExps()
                                .get(rightPositionInEnumerable))
                                .getIndex();
                        }
                        else {
                            rightIndex = rightPositionInEnumerable;
                        }

                        return Collections.singletonList(
                            new TranslationPredicate(
                                left.getIndex(),
                                rightIndex,
                                call,
                                tableSchema
                            ));
                    } else {
                        throw new IllegalArgumentException(
                                "Unable to construct a Kudu Predicate for join condition that doesn't contain two InputRefs");
                    }

                case AND:
                    return call.operands.stream().map(rexNode -> rexNode.accept(this)).reduce(Collections.emptyList(),
                            (left, right) -> {
                                if (left.isEmpty()) {
                                    return right;
                                }
                                if (right.isEmpty()) {
                                    return left;
                                }

                                final ArrayList<TranslationPredicate> merged = new ArrayList<>();
                                merged.addAll(left);
                                merged.addAll(right);
                                return merged;
                            });
                default:
                    throw new IllegalArgumentException(String
                            .format("Unable to Use Nested Loop Join with a this condition: %s call type", callType));

            }
        }
    }
}
