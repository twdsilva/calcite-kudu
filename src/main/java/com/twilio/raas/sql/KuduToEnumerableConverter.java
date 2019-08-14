package com.twilio.raas.sql;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.rel.convert.ConverterImpl;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.util.BuiltInMethod;
import java.util.List;
import org.apache.calcite.linq4j.tree.MethodCallExpression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.prepare.CalcitePrepareImpl;
import org.apache.calcite.runtime.Hook;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.kudu.client.KuduPredicate;
import java.util.Collections;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Type;
import java.lang.reflect.Constructor;
import java.util.Arrays;

import com.google.common.base.Predicates;

/**
 * Rule to convert a relational expression from
 * {@link KuduRel#CONVENTION} to {@link EnumerableConvention}.
 *
 * Bulk of the work is in the implement method which takes an
 * {@link EnumeralbeRelImplementor} and creates
 * {@link org.apache.calcite.linq4j.tree.BlockStatement}.
 */
public class KuduToEnumerableConverter extends ConverterRule {
    public static final ConverterRule INSTANCE =
        new KuduToEnumerableConverter(RelFactories.LOGICAL_BUILDER);

    public KuduToEnumerableConverter(RelBuilderFactory relBuilderFactory) {
        super(RelNode.class, Predicates.<RelNode>alwaysTrue(),
              KuduRel.CONVENTION, EnumerableConvention.INSTANCE,
              relBuilderFactory, "KuduToEnumerableConverterRule");
    }

    @Override
    public RelNode convert(RelNode rel) {
        RelTraitSet newTraitSet = rel.getTraitSet().replace(getOutConvention());
        return new KuduToEnumerableRel(rel.getCluster(), newTraitSet, rel);
    }

    public static class KuduToEnumerableRel extends ConverterImpl
        implements EnumerableRel {
        protected KuduToEnumerableRel(RelOptCluster cluster,
                                      RelTraitSet traits,
                                      RelNode input) {
            super(cluster, ConventionTraitDef.INSTANCE, traits, input);
        }

        @Override
        public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
            return new KuduToEnumerableRel(getCluster(), traitSet, sole(inputs));
        }

        @Override
        public RelOptCost computeSelfCost(RelOptPlanner planner,
                                          RelMetadataQuery mq) {
            return super.computeSelfCost(planner, mq).multiplyBy(.1);
        }

        /**
         * This does the bulk of the work, creating a compile-able query to execute.
         */
        @Override
        public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
            // Generates a call to "query" with the appropriate fields and predicates
            final BlockBuilder list = new BlockBuilder();
            final KuduRel.Implementor kuduImplementor = new KuduRel.Implementor();
            // This goes and visits the entire tree, setting up kuduImplementor
            // with predicates, fields and limits.
            kuduImplementor.visitChild(0, getInput());
            final RelDataType rowType = getRowType();
            final PhysType physType =
                PhysTypeImpl.of(
                                implementor.getTypeFactory(), rowType,
                                pref.prefer(JavaRowFormat.ARRAY));

            // Now build the Java code that represents the Physical scan of a
            // Kudu Table.
            final Expression predicates = createAllScans(kuduImplementor.predicates);

            final Expression fields =
                list.append("kuduFields",
                            constantArrayList(kuduImplementor.kuduProjectedColumns,
                                              Integer.class));
            final Expression table =
                list.append("table",
                            kuduImplementor.table.getExpression(
                                                                CalciteKuduTable.KuduQueryable.class));

            Expression enumerable =
                list.append("enumerable",
                            Expressions.call(table,
                                             KuduMethod.KUDU_QUERY_METHOD.method, predicates, fields));

            //Hook.QUERY_PLAN.run(predicates);
            list.add(
                     Expressions.return_(null, enumerable));
            return implementor.result(physType, list.toBlock());
        }

        /** E.g. {@code constantArrayList("x", "y")} returns
         * "Arrays.asList('x', 'y')".
         * @param values list of values
         * @param clazz runtime class representing each element in the list
         * @param <T> type of elements in the list
         * @return method call which creates a list
         */
        private static <T> MethodCallExpression constantArrayList(List<T> values, Class clazz) {
            return Expressions.call(BuiltInMethod.ARRAYS_AS_LIST.method,
                                    Expressions.newArrayInit(clazz, constantList(values)));
        }

        /** E.g. {@code constantList("x", "y")} returns
         * {@code {ConstantExpression("x"), ConstantExpression("y")}}.
         * @param values list of elements
         * @param <T> type of elements inside this list
         * @return list of constant expressions
         */
        private static <T> List<Expression> constantList(List<T> values) {
            return values.stream().map(Expressions::constant).collect(Collectors.toList());
        }

        /**
         * These next three methods are responsible for creating an ArrayList of
         * {@link CalciteKuduPredicate} that are inputs into
         * {@link KuduMethod.KUDU_QUERY_METHOD}.
         */
        private static Expression createAllScans(List<List<CalciteKuduPredicate>> allScans) {
            return Expressions
                .call(BuiltInMethod.ARRAYS_AS_LIST.method,
                      allScans.stream().map(subScan -> createSubScan(subScan)).collect(Collectors.toList()));
        }

        private static Expression createSubScan(List<CalciteKuduPredicate> predicates) {
            return Expressions
                .call(BuiltInMethod.ARRAYS_AS_LIST.method,
                      Expressions.newArrayInit(CalciteKuduPredicate.class,
                                               predicates.stream()
                                               .map(p -> createCalcitePredicate(p))
                                               .collect(Collectors.toList())));
        }

        private static Expression createCalcitePredicate(CalciteKuduPredicate p) {
            try {
                return Expressions.new_(CalciteKuduPredicate.class.getConstructor(String.class,
                                                                                  KuduPredicate.ComparisonOp.class,
                                                                                  Object.class),
                                        Arrays.asList(Expressions.constant(p.columnName),
                                                      Expressions.constant(p.operation.get()),
                                                      Expressions.constant(p.rightHandValue)));
            }
            catch (NoSuchMethodException constrDoesntExist) {
                throw new RuntimeException("Constructor doesnt exist", constrDoesntExist);
            }
        }
    }
}
