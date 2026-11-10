package it.solphivia.mobile;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Cyber-styled programmatic dialog replacing stock white AlertDialogs.
 *  Same palette as the ☰ menu in CompanionInputActivity: panel #131A2E,
 *  border #2A3554, cyan title #00E5FF, text #EAF2FF on deep #0A0E1C. */
final class CyberDialogs {
    private static final int BG = 0xFF131A2E;
    private static final int BG_DEEP = 0xFF0A0E1C;
    private static final int BORDER = 0xFF2A3554;
    private static final int CYAN = 0xFF00E5FF;
    private static final int TEXT = 0xFFEAF2FF;
    private static final int DIM = 0xFF8FA3C8;

    /** Click action for a dialog button. */
    interface Action { void run(Dialog dialog); }

    private CyberDialogs() { }

    /** Fluent builder: title, optional message, optional custom view, buttons. */
    static class Builder {
        private final Activity act;
        private final String title;
        private final String message;
        private View custom;
        private final java.util.List<TextView[]> buttons = new java.util.ArrayList<>();
        private final java.util.List<String[]> labels = new java.util.ArrayList<>();
        private final java.util.List<Action[]> actions = new java.util.ArrayList<>();
        private boolean cancelFinishes;

        Builder(Activity activity, String titleText, String messageText) {
            act = activity;
            title = titleText;
            message = messageText;
        }

        /** Adds a button. role: 0 = primary (cyan), anything else = secondary (dim). */
        Builder button(String label, int role, Action action) {
            buttons.add(new TextView[1]);
            labels.add(new String[]{label, String.valueOf(role)});
            actions.add(new Action[]{action});
            return this;
        }

        Builder customView(View view) { custom = view; return this; }

        /** Cancelling (back key or outside tap) also finishes the host activity. */
        Builder cancelFinishes() { cancelFinishes = true; return this; }

        void show() {
            float d = act.getResources().getDisplayMetrics().density;
            int pad = Math.round(16 * d);
            int half = Math.round(pad / 2);

            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(BG);
            panel.setCornerRadius(Math.round(18 * d));
            panel.setStroke(Math.max(1, Math.round(d)), BORDER);
            box.setBackground(panel);
            box.setPadding(pad, pad, pad, pad);

            TextView titleView = new TextView(act);
            titleView.setText(title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setTextColor(CYAN);
            box.addView(titleView);

            if (message != null && !message.isEmpty()) {
                TextView messageView = new TextView(act);
                messageView.setText(message);
                messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                messageView.setTextColor(TEXT);
                messageView.setLineSpacing(Math.round(2 * d), 1f);
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
                mp.setMargins(0, half, 0, 0);
                messageView.setLayoutParams(mp);
                box.addView(messageView);
            }

            if (custom != null) {
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
                cp.setMargins(0, half, 0, 0);
                custom.setLayoutParams(cp);
                box.addView(custom);
            }

            if (!buttons.isEmpty()) {
                boolean vertical = buttons.size() > 2;
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.setMargins(0, pad, 0, 0);
                row.setLayoutParams(rp);
                for (int i = 0; i < buttons.size(); i++) {
                    final int index = i;
                    TextView button = new TextView(act);
                    button.setText(labels.get(i)[0]);
                    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    button.setTypeface(null, Typeface.BOLD);
                    boolean primary = "0".equals(labels.get(i)[1]);
                    button.setTextColor(primary ? CYAN : DIM);
                    button.setGravity(Gravity.CENTER);
                    GradientDrawable chip = new GradientDrawable();
                    chip.setColor(BG_DEEP);
                    chip.setCornerRadius(Math.round(12 * d));
                    chip.setStroke(Math.max(1, Math.round(d)), primary ? CYAN : BORDER);
                    button.setBackground(chip);
                    button.setPadding(half, half, half, half);
                    LinearLayout.LayoutParams bp = vertical
                            ? new LinearLayout.LayoutParams(-1, -2)
                            : new LinearLayout.LayoutParams(0, -2, 1f);
                    if (i > 0) bp.setMargins(vertical ? 0 : half, vertical ? half : 0, 0, 0);
                    button.setLayoutParams(bp);
                    button.setOnClickListener(v -> {
                        Action action = actions.get(index)[0];
                        Dialog open = (Dialog) v.getTag();
                        if (action != null && open != null) action.run(open);
                        else if (open != null) open.dismiss();
                    });
                    buttons.set(i, new TextView[]{button});
                    row.addView(button);
                }
                box.addView(row);
            }

            Dialog dialog = new Dialog(act, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
            Window window = dialog.getWindow();
            if (window != null) window.setBackgroundDrawableResource(android.R.color.transparent);
            dialog.setContentView(box);
            dialog.setCanceledOnTouchOutside(true);
            if (cancelFinishes) dialog.setOnCancelListener(dlg -> act.finish());
            for (int i = 0; i < buttons.size(); i++) buttons.get(i)[0].setTag(dialog);
            dialog.show();
        }
    }

    /** EditText styled for the cyber dialog (dark field, cyan text, dim hint). */
    static EditText styledInput(Activity act, String hint, String value) {
        float d = act.getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * d);
        EditText input = new EditText(act);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setHintTextColor(DIM);
        input.setTextColor(CYAN);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable field = new GradientDrawable();
        field.setColor(BG_DEEP);
        field.setCornerRadius(Math.round(10 * d));
        field.setStroke(Math.max(1, Math.round(d)), BORDER);
        input.setBackground(field);
        input.setPadding(pad, pad, pad, pad);
        if (value != null) { input.setText(value); input.setSelectAllOnFocus(true); }
        return input;
    }
}
