package org.geysermc.generator.state.type;

import org.apache.commons.lang3.tuple.Pair;
import org.geysermc.generator.state.StateMapper;
import org.geysermc.generator.state.StateRemapper;

@StateRemapper(value = "facing", blockRegex = ".*chiseled_bookshelf$")
public class ChiseledBookshelfFacingMapper extends StateMapper<Integer> {
    @Override
    public Pair<String, Integer> translateState(String fullIdentifier, String value) {
        int direction = switch (value) {
            case "south" -> 0;
            case "west" -> 1;
            case "north" -> 2;
            case "east" -> 3;
            default -> throw new IllegalArgumentException("Got " + value + " instead of a cardinal direction");
        };
        return Pair.of("direction", direction);
    }
}
