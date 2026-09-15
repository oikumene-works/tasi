/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Explicit interval entry: there is intentionally no default recording interval. */
final class TA612CRecDialog {
    static Long open(Shell parent) {
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText("Download TA612C REC memory");
        dialog.setLayout(new GridLayout(2, false));
        Label explanation = new Label(dialog, SWT.WRAP);
        explanation.setText("Enter the interval used for this recording. It is not read from the meter.\n"
                + "Time will be inferred from sample order; the absolute recording date is unknown.\n"
                + "Transfer completion is inferred from silence and remains unverified.\n"
                + "Valid readings from partially connected probes are retained in separate segments.\n"
                + "Downloading does not erase memory or change recording settings.");
        GridData wide = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        wide.widthHint = 540;
        explanation.setLayoutData(wide);
        new Label(dialog, SWT.NONE).setText("Recording interval (seconds):");
        Text interval = new Text(dialog, SWT.BORDER);
        interval.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label error = new Label(dialog, SWT.WRAP);
        GridData errorData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        errorData.widthHint = 540; errorData.heightHint = 44;
        error.setLayoutData(errorData);
        Button download = new Button(dialog, SWT.PUSH);
        download.setText("Download REC");
        Button cancel = new Button(dialog, SWT.PUSH);
        cancel.setText("Cancel");
        Long[] result = {null};
        download.addListener(SWT.Selection, e -> {
            try { result[0] = TA612CRecImport.parseIntervalMs(interval.getText()); dialog.dispose(); }
            catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); dialog.layout(); }
        });
        cancel.addListener(SWT.Selection, e -> dialog.dispose());
        dialog.setDefaultButton(download);
        dialog.pack(); dialog.open(); interval.setFocus();
        while (!dialog.isDisposed()) if (!dialog.getDisplay().readAndDispatch()) dialog.getDisplay().sleep();
        return result[0];
    }
}
