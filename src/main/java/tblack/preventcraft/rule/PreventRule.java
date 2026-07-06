package tblack.preventcraft.rule;

import com.google.gson.annotations.SerializedName;

public final class PreventRule {
    @SerializedName("Id")
    public String Id = "rule-1";

    @SerializedName("Enabled")
    public boolean Enabled = true;

    @SerializedName("Type")
    public RuleType Type = RuleType.CRAFT_ITEM;

    @SerializedName("Target")
    public String Target = "";

    @SerializedName("Action")
    public RuleAction Action = RuleAction.DENY;

    @SerializedName("Scope")
    public RuleScope Scope = RuleScope.EVERYONE;

    @SerializedName("Group")
    public String Group = "";

    @SerializedName("Player")
    public String Player = "";

    @SerializedName("Note")
    public String Note = "";

    public PreventRule copy() {
        PreventRule copy = new PreventRule();
        copy.Id = Id;
        copy.Enabled = Enabled;
        copy.Type = Type;
        copy.Target = Target;
        copy.Action = Action;
        copy.Scope = Scope;
        copy.Group = Group;
        copy.Player = Player;
        copy.Note = Note;
        return copy;
    }
}
