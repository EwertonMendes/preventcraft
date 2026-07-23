package tblack.preventcraft.config;

import com.google.gson.annotations.SerializedName;
import tblack.preventcraft.ModConstants;
import tblack.preventcraft.importer.CraftRestrictMode;
import tblack.preventcraft.rule.PreventRule;
import tblack.preventcraft.rule.RestrictionMode;

import java.util.ArrayList;
import java.util.List;

public final class PreventCraftConfig {
    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final int MAX_RULES = 10000;

    @SerializedName("SchemaVersion")
    public int SchemaVersion = CURRENT_SCHEMA_VERSION;

    @SerializedName("Enabled")
    public boolean Enabled = true;

    @SerializedName("Mode")
    public RestrictionMode Mode = RestrictionMode.BLACKLIST;

    @SerializedName("HideBlockedRecipes")
    public boolean HideBlockedRecipes = true;

    @SerializedName("Debug")
    public boolean Debug = false;

    @SerializedName("Commands")
    public Commands Commands = new Commands();

    @SerializedName("Feedback")
    public Feedback Feedback = new Feedback();

    @SerializedName("Migration")
    public Migration Migration = new Migration();

    @SerializedName("Rules")
    public List<PreventRule> Rules = new ArrayList<>();

    public static final class Commands {
        @SerializedName("Primary")
        public String Primary = "preventcraft";

        @SerializedName("Aliases")
        public List<String> Aliases = new ArrayList<>(List.of("pcraft"));

        @SerializedName("UsePermission")
        public String UsePermission = ModConstants.USE_PERMISSION;

        @SerializedName("AdminPermission")
        public String AdminPermission = ModConstants.ADMIN_PERMISSION;
    }

    public static final class Feedback {
        @SerializedName("SendCraftDeniedMessage")
        public boolean SendCraftDeniedMessage = true;

        @SerializedName("SendBenchDeniedMessage")
        public boolean SendBenchDeniedMessage = true;

        @SerializedName("SendDeniedSound")
        public boolean SendDeniedSound = true;

        @SerializedName("CraftDeniedMessage")
        public String CraftDeniedMessage = "You cannot craft this item.";

        @SerializedName("BenchCraftDeniedMessage")
        public String BenchCraftDeniedMessage = "You cannot craft this bench.";

        @SerializedName("BenchAccessDeniedMessage")
        public String BenchAccessDeniedMessage = "You cannot use this bench.";

        @SerializedName("DeniedSound")
        public String DeniedSound = "SFX_Antelope_Alerted";
    }

    public static final class Migration {
        @SerializedName("CraftRestrictMode")
        public CraftRestrictMode CraftRestrictModeValue = CraftRestrictMode.DENY;

        @SerializedName("IncludeUsers")
        public boolean IncludeUsers = false;
    }
}
