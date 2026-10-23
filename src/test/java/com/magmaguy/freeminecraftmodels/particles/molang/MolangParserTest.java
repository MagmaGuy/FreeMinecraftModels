package com.magmaguy.freeminecraftmodels.particles.molang;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MolangParserTest {

    private static double eval(String source) {
        return MolangParser.parse(source).evaluate(new MolangContext(new SplittableRandom(1)));
    }

    @Test
    void arithmeticFollowsPrecedence() {
        assertEquals(7, eval("1 + 2 * 3"));
        assertEquals(9, eval("(1 + 2) * 3"));
        assertEquals(-4, eval("-2 * 2"));
        assertEquals(2.5, eval("10 / 4"));
        assertEquals(1, eval("!0"));
    }

    @Test
    void trigonometryUsesDegrees() {
        assertEquals(1, eval("math.sin(90)"), 1e-9);
        assertEquals(-1, eval("Math.Cos(180)"), 1e-9);
        assertEquals(45, eval("math.atan2(1, 1)"), 1e-9);
        assertEquals(30, eval("math.asin(0.5)"), 1e-9);
    }

    @Test
    void comparisonsLogicAndConditionals() {
        assertEquals(1, eval("2 > 1 && 1 <= 1"));
        assertEquals(0, eval("2 < 1 || 1 != 1"));
        assertEquals(5, eval("1 > 0 ? 5 : 6"));
        assertEquals(6, eval("0 ? 5 : 6"));
        assertEquals(3, eval("query.unknown ?? 3"));
    }

    @Test
    void assignmentsPersistAndReturnEndsTheProgram() {
        MolangContext context = new MolangContext(new SplittableRandom(1));
        double result = MolangParser.parse("v.a = 2; variable.b = v.a * 3; return v.b;").evaluate(context);
        assertEquals(6, result);
        assertEquals(6, context.get("variable.b"));
        assertEquals(0, MolangParser.parse("v.c = 4;").evaluate(context), "Programs without return yield 0");
        assertEquals(4, context.get("v.c"));
    }

    @Test
    void returningLastLineReadsAProgramAsBlockbenchDoes() {
        String program = "v.t = q.anim_time * 60;\nv.w = math.sin(v.t);\nmath.atan2(1, 1) * 2\n";
        String explicit = MolangParser.returningLastLine(program);
        assertEquals("v.t = q.anim_time * 60;\nv.w = math.sin(v.t);\nreturn math.atan2(1, 1) * 2\n", explicit);
        assertEquals(0, eval(program), "Bedrock gives a program without return 0");
        assertEquals(90, eval(explicit), 1e-9, "Blockbench gives it its last line");
        assertEquals("v.a = 1; v.b = 2;", MolangParser.returningLastLine("v.a = 1; v.b = 2;"), "an assignment stays last");
        assertEquals("v.a = 1; return v.a;", MolangParser.returningLastLine("v.a = 1; return v.a;"), "already returns");
        assertEquals("math.sin(30)", MolangParser.returningLastLine("math.sin(30)"), "a single expression");
        assertEquals("loop(2, {v.i = v.i + 1;}); return v.i",
                MolangParser.returningLastLine("loop(2, {v.i = v.i + 1;}); v.i"), "semicolons inside braces are not lines");
    }

    @Test
    void unknownNamesReadAsZeroAndDivisionByZeroIsZero() {
        assertEquals(0, eval("query.anything + variable.missing"));
        assertEquals(0, eval("1 / 0"));
        assertEquals(0, eval("'text'"));
    }

    @Test
    void randomStaysInRangeAndRepeatsForTheSameSeed() {
        MolangExpression expression = MolangParser.parse("math.random(2, 4)");
        double first = expression.evaluate(new MolangContext(new SplittableRandom(7)));
        double again = expression.evaluate(new MolangContext(new SplittableRandom(7)));
        assertTrue(first >= 2 && first <= 4, "value " + first);
        assertEquals(first, again);
        double integer = MolangParser.parse("math.random_integer(1, 3)")
                .evaluate(new MolangContext(new SplittableRandom(3)));
        assertTrue(integer == 1 || integer == 2 || integer == 3, "value " + integer);
    }

    @Test
    void mathHelpers() {
        assertEquals(5, eval("math.clamp(9, 0, 5)"));
        assertEquals(2.5, eval("math.lerp(0, 10, 0.25)"));
        assertEquals(8, eval("math.pow(2, 3)"));
        assertEquals(1, eval("math.mod(7, 3)"));
        assertEquals(3, eval("math.max(1, 3)"));
        assertEquals(Math.PI, eval("math.pi"), 1e-12);
        assertEquals(0.5, eval("math.hermite_blend(0.5)"), 1e-12);
    }

    @Test
    void malformedSourceReportsOffset() {
        MolangParseException failure = assertThrows(MolangParseException.class, () -> MolangParser.parse("1 +"));
        assertEquals(3, failure.getOffset());
    }
}
