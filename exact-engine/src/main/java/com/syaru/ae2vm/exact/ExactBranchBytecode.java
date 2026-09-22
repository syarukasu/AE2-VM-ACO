package com.syaru.ae2vm.exact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ordered recipe instructions. Constants never narrow to an AE2 stack count. */
public final class ExactBranchBytecode<K> {
    static final int REQUEST = 1, RETURN_CONTAINERS = 2, INSERT = 3, RECORD = 4, HALT = 255;
    private final String id;
    private final List<InputSlot<K>> inputs;
    private final Map<K, BigInteger> outputs;
    private final List<K> outputKeys;
    private final BigInteger[] constants;
    private final int[] code;

    public record Stack<K>(K key, BigInteger amount) {
        public Stack { Objects.requireNonNull(key); positive(amount); }
    }
    public record InputSlot<K>(List<Stack<K>> alternatives, BigInteger templateAmount) {
        public InputSlot {
            alternatives = List.copyOf(alternatives);
            if (alternatives.isEmpty()) throw new IllegalArgumentException("empty input domain");
            positive(templateAmount);
        }
    }

    public ExactBranchBytecode(String id, List<InputSlot<K>> inputs, Map<K, BigInteger> outputs) {
        this.id = Objects.requireNonNull(id);
        this.inputs = List.copyOf(inputs);
        if (id.isBlank() || outputs.isEmpty() || inputs.size() > 1_048_576 || outputs.size() > 1_048_576)
            throw new IllegalArgumentException("invalid pattern size/id");
        var copy = new LinkedHashMap<K, BigInteger>();
        outputs.forEach((key, amount) -> { Objects.requireNonNull(key); positive(amount); copy.put(key, amount); });
        this.outputs = Collections.unmodifiableMap(copy);
        outputKeys = List.copyOf(copy.keySet());
        var words = new ArrayList<Integer>();
        var values = new ArrayList<BigInteger>();
        for (int slot = 0; slot < inputs.size(); slot++) {
            words.add(REQUEST); words.add(slot); words.add(values.size());
            values.add(inputs.get(slot).alternatives().get(0).amount());
        }
        words.add(RETURN_CONTAINERS);
        for (int output = 0; output < outputKeys.size(); output++) {
            words.add(INSERT); words.add(output); words.add(values.size());
            values.add(copy.get(outputKeys.get(output)));
        }
        words.add(RECORD); words.add(HALT);
        code = words.stream().mapToInt(Integer::intValue).toArray();
        constants = values.toArray(BigInteger[]::new);
    }

    public String id() { return id; }
    public List<InputSlot<K>> inputs() { return inputs; }
    public Map<K, BigInteger> outputs() { return outputs; }
    public BigInteger outputAmount(K key) { return outputs.getOrDefault(key, BigInteger.ZERO); }
    public int instructionWords() { return code.length; }
    int word(int pc) { return code[pc]; }
    BigInteger constant(int index) { return constants[index]; }
    K outputKey(int index) { return outputKeys.get(index); }

    private static void positive(BigInteger amount) {
        if (Objects.requireNonNull(amount).signum() <= 0) throw new IllegalArgumentException("positive coefficient required");
    }
}
