package org.geysermc.generator;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Getter @Setter
public class BlockEntry {

    @SerializedName("bedrock_identifier")
    private String bedrockIdentifier;

    @SerializedName("bedrock_states")
    private JsonElement bedrockStates;
}
