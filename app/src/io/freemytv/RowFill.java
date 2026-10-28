package io.freemytv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which titles a row shows. Rows are in display order (movies, then shows). An earlier row keeps a title; a later
 * row skips it and takes the next candidate, so a row stays full when an earlier one already used some of its list.
 * Nothing is shuffled: each row keeps the order its candidates arrived in.
 */
final class RowFill {
    private RowFill() {}

    /**
     * @param rows each inner list is one row's candidates, already in the order to show them
     * @param size how many titles a row should show
     * @return one list per row, each with at most {@code size} ids, and no id in more than one row
     */
    static List<List<String>> assign(List<List<String>> rows, int size) {
        List<List<String>> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        if (rows == null || size <= 0) return out;
        for (List<String> candidates : rows) {
            List<String> row = new ArrayList<>();
            if (candidates != null) {
                for (String id : candidates) {
                    if (row.size() >= size) break;
                    if (id == null || id.isEmpty() || !used.add(id)) continue;
                    row.add(id);
                }
            }
            out.add(row);
        }
        return out;
    }
}
