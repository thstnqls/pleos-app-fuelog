package com.pleos.fuelog.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

/** Keeps manually entered dates in YYYY-MM-DD form; validation stays with the editor. */
public final class DateInputFormatter implements TextWatcher {
    private final EditText input;
    private boolean changing;
    private int deletedSeparator = -1;

    public DateInputFormatter(EditText input) { this.input = input; }

    @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        if (!changing) deletedSeparator = count == 1 && after == 0 && text.charAt(start) == '-' ? start : -1;
    }

    @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }

    @Override public void afterTextChanged(Editable text) {
        if (changing) return;
        int cursor = Math.max(0, input.getSelectionStart());
        StringBuilder digits = new StringBuilder();
        int digitsBeforeCursor = 0;
        for (int i = 0; i < text.length(); i++) {
            char digit = text.charAt(i);
            if (digit >= '0' && digit <= '9' && digits.length() < 8) {
                digits.append(digit);
                if (i < cursor) digitsBeforeCursor++;
            }
        }
        // Backspacing over an automatic separator should also remove the preceding digit.
        if (deletedSeparator >= 0 && digitsBeforeCursor > 0) {
            digits.deleteCharAt(--digitsBeforeCursor);
        }
        StringBuilder formatted = new StringBuilder();
        int selection = 0;
        for (int i = 0; i < digits.length(); i++) {
            if (i == 4 || i == 6) formatted.append('-');
            formatted.append(digits.charAt(i));
            if (i < digitsBeforeCursor) selection = formatted.length();
        }
        if (!text.toString().contentEquals(formatted)) {
            changing = true;
            try {
                text.replace(0, text.length(), formatted);
                input.setSelection(selection);
            } finally { changing = false; }
        }
    }
}
