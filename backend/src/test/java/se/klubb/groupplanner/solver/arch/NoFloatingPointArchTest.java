package se.klubb.groupplanner.solver.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

/**
 * CLAUDE.md determinism rule, build-breaking: "No float/double/BigDecimal in solver.domain or
 * solver.constraints". Cross-platform score equality depends on integer-only arithmetic (IEEE-754
 * double accumulation order is not guaranteed bit-identical across JIT/hardware).
 *
 * <p>docs/design/04-solver.md §3.2's sketch acknowledges this needs a custom check ("implemented
 * with ArchUnit field/method-parameter/return-type predicates; plus a bytecode scan") rather than a
 * single built-in rule; this test checks field types, method parameter types, and method return
 * types (the realistic surface for a persisted/exchanged float or double) plus a dependency check
 * for {@code java.math.BigDecimal}. Local-variable-only float/double usage (never a field/parameter/
 * return type) is NOT caught — considered acceptable residual scope for a defensive CI gate, since
 * any such value would have nowhere semantically meaningful to go without eventually crossing a
 * field/parameter/return boundary this test does cover.
 */
class NoFloatingPointArchTest {

    private static final String[] PACKAGES = {"se.klubb.groupplanner.solver.domain", "se.klubb.groupplanner.solver.constraints"};
    private static final JavaClasses IMPORTED = new ClassFileImporter().importPackages(PACKAGES);

    @Test
    void solverPackagesAreActuallyImported() {
        // Guards against a silent false-pass if the package names above ever drift from reality.
        assertThat(IMPORTED).isNotEmpty();
    }

    @Test
    void noFieldsUseFloatOrDouble() {
        ArchRule rule = classes().should(new ArchCondition<JavaClass>("declare no float/double fields") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaField field : item.getFields()) {
                    String typeName = field.getRawType().getName();
                    if (isFloatingPoint(typeName)) {
                        events.add(SimpleConditionEvent.violated(
                                field, field.getFullName() + " has forbidden floating-point type " + typeName));
                    }
                }
            }
        });
        rule.check(IMPORTED);
    }

    @Test
    void noMethodsUseFloatOrDoubleParametersOrReturnType() {
        ArchRule rule = classes().should(new ArchCondition<JavaClass>("declare no float/double method signatures") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    String returnType = method.getRawReturnType().getName();
                    if (isFloatingPoint(returnType)) {
                        events.add(SimpleConditionEvent.violated(
                                method, method.getFullName() + " returns forbidden floating-point type " + returnType));
                    }
                    for (JavaParameter parameter : method.getParameters()) {
                        String paramType = parameter.getRawType().getName();
                        if (isFloatingPoint(paramType)) {
                            events.add(SimpleConditionEvent.violated(
                                    method, method.getFullName() + " has forbidden floating-point parameter " + paramType));
                        }
                    }
                }
            }
        });
        rule.check(IMPORTED);
    }

    @Test
    void noClassDependsOnBigDecimal() {
        ArchRule rule = noClasses().should().dependOnClassesThat().haveFullyQualifiedName("java.math.BigDecimal");
        rule.check(IMPORTED);
    }

    private static boolean isFloatingPoint(String typeName) {
        return "double".equals(typeName) || "float".equals(typeName)
                || "java.lang.Double".equals(typeName) || "java.lang.Float".equals(typeName);
    }
}
