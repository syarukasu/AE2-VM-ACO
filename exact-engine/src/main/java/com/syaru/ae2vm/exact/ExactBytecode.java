package com.syaru.ae2vm.exact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** LGPL-3.0; adapted from AE2-VM CraftingBytecode/Opcode. See META-INF/NOTICE.txt. */
public final class ExactBytecode {
    static final int PUSH = 0x01, MUL = 0x04, DIV_ROUNDUP = 0x05, EXTRACT = 0x06;
    static final int MISSING = 0x09, DUP = 0x0a, POP = 0x0b, PATTERN = 0x0d;
    static final int CALL_BY_KEY = 0x10, LOAD_DEMAND = 0x15, JUMP_ZERO = 0x16, HALT = 0xff;
    private final int[] code;
    private final BigInteger[] constants;
    private final int nodes;

    public record Input(int key, BigInteger amount) {
        public Input(int key, long amount) { this(key, BigInteger.valueOf(amount)); }
        public Input {
            if (key < 0 || amount == null || amount.signum() <= 0) throw new IllegalArgumentException("invalid input");
        }
    }
    public record Node(BigInteger outputAmount, List<Input> inputs) {
        public Node(long outputAmount, List<Input> inputs) { this(BigInteger.valueOf(outputAmount), inputs); }
        public Node {
            inputs = List.copyOf(inputs);
            if (outputAmount == null || outputAmount.signum() < 0 || (outputAmount.signum() == 0 && !inputs.isEmpty()))
                throw new IllegalArgumentException("invalid node");
        }
    }

    private ExactBytecode(int[] code, BigInteger[] constants, int nodes) {
        this.code = code;
        this.constants = constants;
        this.nodes = nodes;
    }

    /** Topologically ordered, fixed aggregate demand program; not arbitrary AE2 eligibility. */
    public static ExactBytecode compile(List<Node> source) {
        List<Node> nodes = List.copyOf(source);
        if (nodes.isEmpty() || nodes.size() > 1_048_576)
            throw new IllegalArgumentException("invalid node count");
        var b = new Builder();
        int edges = 0;
        for (int node = 0; node < nodes.size(); node++) {
            Node n = nodes.get(node);
            b.emit(LOAD_DEMAND, node);
            b.emit(EXTRACT, node);
            b.emit(JUMP_ZERO, 0);
            int end = b.code.size() - 1;
            if (n.outputAmount().signum() == 0) {
                b.emit(MISSING, node);
            } else {
                b.push(n.outputAmount());
                b.emit(DIV_ROUNDUP);
                b.emit(DUP);
                b.emit(PATTERN, node);
                for (Input input : n.inputs()) {
                    if (++edges > 4_194_304 || input.key() <= node || input.key() >= nodes.size())
                        throw new IllegalArgumentException("non-forward or oversized dependency");
                    b.emit(DUP);
                    b.push(input.amount());
                    b.emit(MUL);
                    b.emit(CALL_BY_KEY, input.key());
                }
                b.emit(POP);
            }
            b.code.set(end, b.code.size());
        }
        b.emit(HALT);
        return new ExactBytecode(b.code.stream().mapToInt(Integer::intValue).toArray(),
                b.constants.toArray(BigInteger[]::new), nodes.size());
    }

    public int nodeCount() { return nodes; }
    public int instructionWords() { return code.length; }
    int word(int pc) { return code[pc]; }
    BigInteger constant(int index) { return constants[index]; }

    private static final class Builder {
        final List<Integer> code = new ArrayList<>();
        final List<BigInteger> constants = new ArrayList<>();
        void emit(int... words) { for (int word : words) code.add(word); }
        void push(BigInteger value) {
            int index = constants.size();
            constants.add(value);
            emit(PUSH, index);
        }
    }
}
