package dk.casa.streamliner.other.examples;

import java.util.stream.IntStream;

public class SumRange {
    public static void main(String[] args) {
        int sum = IntStream.range(0, 100)
                .map(x -> x * x)
                .filter(x -> x % 2 == 0)
                .sum();
        System.out.println(sum);
    }
}
