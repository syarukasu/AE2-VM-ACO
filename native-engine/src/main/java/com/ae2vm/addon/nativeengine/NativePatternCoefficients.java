package com.ae2vm.addon.nativeengine;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.pattern.AEProcessingPattern;
import java.math.BigInteger;
import java.util.List;

/** #218: runtime coefficient decoding without the retired graph catalog. */
final class NativePatternCoefficients {
    record Coefficients(IPatternDetails pattern, BigInteger scale) { }

    private NativePatternCoefficients() { }

    static Coefficients read(IPatternDetails source) {
        String name = "com.extendedae_plus.api.crafting.ScaledProcessingPattern";
        var type = source.getClass();
        if (!type.getName().equals(name) && !type.getName().equals(name + "Adv"))
            return new Coefficients(source, BigInteger.ONE);
        try {
            for (var method : List.of("getDefinition", "getInputs", "getOutputs")) {
                var declared = type.getMethod(method);
                if (!declared.getDeclaringClass().getName().equals(name)
                        || !java.lang.reflect.Modifier.isFinal(declared.getModifiers()))
                    throw new UnsupportedOperationException("Changed scaled pattern contract: " + method);
            }
            var originalGetter = type.getMethod("getOriginal");
            var multiplierGetter = type.getMethod("getMultiplier");
            if (!originalGetter.getDeclaringClass().getName().equals(name)
                    || !multiplierGetter.getDeclaringClass().getName().equals(name)
                    || originalGetter.getReturnType() != AEProcessingPattern.class || multiplierGetter.getReturnType() != long.class)
                throw new UnsupportedOperationException("Changed scaled pattern coefficient contract");
            var original = (AEProcessingPattern) originalGetter.invoke(source);
            long multiplier = (Long) multiplierGetter.invoke(source);
            if (original == null || multiplier <= 0) throw new IllegalArgumentException("Invalid scaled pattern coefficients");
            for (var method : List.of("getInputs", "getOutputs"))
                if (original.getClass().getMethod(method).getDeclaringClass() != AEProcessingPattern.class)
                    throw new UnsupportedOperationException("Changed underlying processing pattern semantics");
            return new Coefficients(original, BigInteger.valueOf(multiplier));
        } catch (java.lang.reflect.InvocationTargetException failed) {
            if (failed.getCause() instanceof Error fatal) throw fatal;
            throw new IllegalStateException("Scaled pattern coefficient read failed", failed.getCause());
        } catch (ReflectiveOperationException unsupported) {
            throw new UnsupportedOperationException("Unsupported scaled pattern version", unsupported);
        }
    }
}
