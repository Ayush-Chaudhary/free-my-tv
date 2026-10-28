package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Checks that a title stays in the earlier row and later rows fill up from what is left. */
public class RowFillTest {
    public static void main(String[] args) {
        int failed = 0;
        failed += expect("earlier row wins, later row backfills",
                assign(2, rows(list("movie:1", "movie:2", "movie:3"), list("movie:2", "movie:4", "movie:5"), list("tv:1", "movie:1"))),
                rows(list("movie:1", "movie:2"), list("movie:4", "movie:5"), list("tv:1")));
        failed += expect("ten from a longer pool, order kept",
                assign(10, rows(list("movie:3", "movie:1", "movie:2", "movie:4", "movie:5", "movie:6", "movie:7", "movie:8", "movie:9", "movie:10", "movie:11"))),
                rows(list("movie:3", "movie:1", "movie:2", "movie:4", "movie:5", "movie:6", "movie:7", "movie:8", "movie:9", "movie:10")));
        failed += expect("short pool stays short",
                assign(10, rows(list("movie:1", "movie:2"))),
                rows(list("movie:1", "movie:2")));
        failed += expect("movie and show ids are different titles",
                assign(1, rows(list("movie:1"), list("tv:1"))),
                rows(list("movie:1"), list("tv:1")));
        failed += expect("a repeat inside one row is skipped",
                assign(2, rows(list("movie:1", "movie:1", "movie:2"))),
                rows(list("movie:1", "movie:2")));
        failed += expect("spares in an earlier row stay available",
                assign(1, rows(list("movie:1", "movie:9"), list("movie:9", "movie:8"))),
                rows(list("movie:1"), list("movie:9")));
        if (failed > 0) {
            System.err.println(failed + " row-fill check(s) failed");
            System.exit(1);
        }
        System.out.println("Row fill checks passed.");
    }

    private static List<List<String>> assign(int size, List<List<String>> rows) {
        return RowFill.assign(rows, size);
    }

    private static List<List<String>> rows(List<String>... parts) {
        return new ArrayList<>(Arrays.asList(parts));
    }

    private static List<String> list(String... ids) {
        return new ArrayList<>(Arrays.asList(ids));
    }

    private static int expect(String name, List<List<String>> got, List<List<String>> expected) {
        if (got.equals(expected)) return 0;
        System.err.println(name);
        System.err.println("  got:  " + got);
        System.err.println("  want: " + expected);
        return 1;
    }
}
