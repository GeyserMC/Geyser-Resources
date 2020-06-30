package org.geysermc.generator.state.type;

import org.geysermc.generator.Pair;
import org.geysermc.generator.state.StateMapper;
import org.geysermc.generator.state.StateRemapper;

@StateRemapper(value = "facing", blockRegex = ".*_fence_gate.?$")
public class FenceGateFacingMapper extends StateMapper<Integer> {

    @Override
    public Pair<String, Integer> translateState(String fullIdentifier, String value) {
        int direction = 0;
        switch (value) {
            case "north":
                direction = 2;
                break;
            case "west":
                direction = 1;
                break;
            case "south":
                direction = 0;
                break;
            case "east":
                direction = 3;
                break;
        }
        return new Pair<>("direction", direction);
    }
}
