package org.geysermc.generator.state.type;

import org.apache.commons.lang3.tuple.Pair;
import org.geysermc.generator.state.StateMapper;
import org.geysermc.generator.state.StateRemapper;

@StateRemapper(value = "waterlogged", blockRegex = {".*_fence$", "^((?!nether).)*$", "^((?!mangrove).)*$", "^((?!crimson).)*$", "^((?!warped).)*$", "^((?!bamboo).)*$", "^((?!cherry).)*$"})
public class FenceWoodTypeMapper extends StateMapper<String> {

    @Override
    public Pair<String, String> translateState(String fullIdentifier, String value) {
        return Pair.of("wood_type", fullIdentifier.substring(10, fullIdentifier.indexOf("_fence")));
    }
}
