package dk.casa.streamliner.other.winterbe;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @author Benjamin Winterberg
 */
public class Streams3 {

    public static final int MAX = 1000000;

    public static void main(String[] args) {
        List<String> values = new ArrayList<>(MAX);
        for (int i = 0; i < MAX; i++) {
            UUID uuid = UUID.randomUUID();
            values.add(uuid.toString());
        }

        // sequential

        long t0 = System.nanoTime();

        long count = values.stream().sorted().count();
        System.out.println(count);

        long t1 = System.nanoTime();

        //long millis = TimeUnit.NANOSECONDS.toMillis(t1 - t0);
        //System.out.println(String.format("sequential sort took: %d ms", millis));
    }
}
