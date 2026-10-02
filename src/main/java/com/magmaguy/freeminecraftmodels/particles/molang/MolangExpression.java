package com.magmaguy.freeminecraftmodels.particles.molang;

/**
 * A compiled Molang program. Evaluation never throws for runtime conditions such as unknown
 * variables or division by zero; those follow Bedrock and read as 0.
 */
@FunctionalInterface
public interface MolangExpression {
    MolangExpression ZERO = context -> 0;

    double evaluate(MolangContext context);

    static MolangExpression constant(double value) {
        return context -> value;
    }
}
