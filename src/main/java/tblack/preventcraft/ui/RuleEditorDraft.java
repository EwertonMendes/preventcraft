package tblack.preventcraft.ui;

import tblack.preventcraft.rule.PreventRule;
import tblack.preventcraft.rule.RuleAction;
import tblack.preventcraft.rule.RuleScope;
import tblack.preventcraft.rule.RuleType;

public final class RuleEditorDraft {
    public String id;
    public boolean enabled;
    public RuleType type;
    public String target;
    public RuleAction action;
    public RuleScope scope;
    public String group;
    public String player;
    public String note;

    public static RuleEditorDraft fromRule(PreventRule rule) {
        RuleEditorDraft draft = new RuleEditorDraft();
        draft.id = rule.Id;
        draft.enabled = rule.Enabled;
        draft.type = rule.Type;
        draft.target = rule.Target;
        draft.action = rule.Action;
        draft.scope = rule.Scope;
        draft.group = rule.Group;
        draft.player = rule.Player;
        draft.note = rule.Note;
        return draft;
    }


    public RuleEditorDraft copy() {
        RuleEditorDraft draft = new RuleEditorDraft();
        draft.id = id;
        draft.enabled = enabled;
        draft.type = type;
        draft.target = target;
        draft.action = action;
        draft.scope = scope;
        draft.group = group;
        draft.player = player;
        draft.note = note;
        return draft;
    }

    public PreventRule toRule() {
        PreventRule rule = new PreventRule();
        rule.Id = id;
        rule.Enabled = enabled;
        rule.Type = type;
        rule.Target = target;
        rule.Action = action;
        rule.Scope = scope;
        rule.Group = group;
        rule.Player = player;
        rule.Note = note;
        return rule;
    }
}
