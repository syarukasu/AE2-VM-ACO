package com.syaru.ae2vm.exact;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Detached quantity interpreter; never accesses Minecraft, inventory owners or external patterns. */
public final class ExactCraftingVM {
    public static final String VERSION = "0.1.2";
    private ExactCraftingVM() {}

    public static Result execute(ExactBytecode program, int root, BigInteger request,
            BigInteger[] inventory, BigInteger maximum, IntConsumer checkpoint) {
        Objects.requireNonNull(program);
        Objects.requireNonNull(checkpoint);
        Objects.requireNonNull(maximum);
        if (maximum.signum() <= 0 || root < 0 || root >= program.nodeCount()
                || inventory.length != program.nodeCount())
            throw new IllegalArgumentException("invalid execution boundary");
        checked(request, maximum);
        BigInteger[] stock = inventory.clone();
        for (BigInteger amount : stock)
            if (Objects.requireNonNull(amount).signum() < 0)
                throw new IllegalArgumentException("negative stock");
        BigInteger[] demand = zeros(stock.length), used = zeros(stock.length);
        BigInteger[] executions = zeros(stock.length), missing = zeros(stock.length);
        demand[root] = request;
        BigInteger[] stack = new BigInteger[4];
        int pc = 0, sp = 0, operations = 0, currentNode = 0;
        for (;;) {
            if ((operations++ & 255) == 0) checkpoint.accept(currentNode);
            switch (program.word(pc++)) {
                case ExactBytecode.LOAD_DEMAND -> {
                    int key = program.word(pc++);
                    currentNode = key + 1;
                    checkpoint.accept(currentNode);
                    stack[sp++] = demand[key];
                }
                case ExactBytecode.EXTRACT -> {
                    int key = program.word(pc++);
                    BigInteger need = stack[--sp];
                    BigInteger taken = need.min(stock[key]);
                    used[key] = taken;
                    stack[sp++] = need.subtract(taken);
                }
                case ExactBytecode.JUMP_ZERO -> {
                    int end = program.word(pc++);
                    if (stack[sp - 1].signum() == 0) { stack[--sp] = null; pc = end; }
                }
                case ExactBytecode.PUSH -> stack[sp++] = program.constant(program.word(pc++));
                case ExactBytecode.DUP -> { stack[sp] = stack[sp - 1]; sp++; }
                case ExactBytecode.POP -> stack[--sp] = null;
                case ExactBytecode.MUL -> {
                    BigInteger b = stack[--sp], a = stack[--sp];
                    stack[sp++] = checked(a.multiply(b), maximum);
                }
                case ExactBytecode.DIV_ROUNDUP -> {
                    BigInteger divisor = stack[--sp], need = stack[--sp];
                    BigInteger[] qr = need.divideAndRemainder(divisor);
                    stack[sp++] = checked(qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE), maximum);
                }
                case ExactBytecode.PATTERN -> executions[program.word(pc++)] = stack[--sp];
                case ExactBytecode.CALL_BY_KEY -> {
                    int key = program.word(pc++);
                    demand[key] = checked(demand[key].add(stack[--sp]), maximum);
                }
                case ExactBytecode.MISSING -> missing[program.word(pc++)] = stack[--sp];
                case ExactBytecode.HALT -> {
                    if (sp != 0) throw new IllegalStateException("unbalanced exact bytecode");
                    return new Result(used, executions, missing);
                }
                default -> throw new IllegalStateException("unsupported exact opcode");
            }
        }
    }

    private static BigInteger checked(BigInteger value, BigInteger maximum) {
        if (Objects.requireNonNull(value).signum() < 0 || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException("exact VM count outside caller limit");
        return value;
    }

    private static BigInteger[] zeros(int count) {
        BigInteger[] values = new BigInteger[count];
        Arrays.fill(values, BigInteger.ZERO);
        return values;
    }

    public static final class Result {
        private final BigInteger[] used, executions, missing;
        Result(BigInteger[] used, BigInteger[] executions, BigInteger[] missing) {
            this.used = used;
            this.executions = executions;
            this.missing = missing;
        }
        public BigInteger usedAt(int key) { return used[key]; }
        public BigInteger executionsAt(int key) { return executions[key]; }
        public BigInteger missingAt(int key) { return missing[key]; }
    }
}
