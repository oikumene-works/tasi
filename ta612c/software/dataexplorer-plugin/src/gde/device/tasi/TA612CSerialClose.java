/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import gde.comm.IDeviceCommPort;
import gde.comm.DeviceJavaSerialCommPortImpl;

/** DataExplorer 4.0.7 closes serial asynchronously. Keep TA612C ownership until it finishes.
 * The core exposes no public close-wait API. This narrow version-specific reflection also
 * works when the plugin and core use different class loaders.
 */
public final class TA612CSerialClose {
    private TA612CSerialClose() { }
    public static void await(IDeviceCommPort port) {
        if (!(port instanceof DeviceJavaSerialCommPortImpl serial)) return;
        Thread closing;
        try {
            var field = DeviceJavaSerialCommPortImpl.class.getDeclaredField("closeThread");
            field.setAccessible(true);
            closing = (Thread) field.get(serial);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Cannot confirm serial port closure with this core version", e);
        }
        if (closing == null) return;
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + 2_000_000_000L;
        try {
            while (closing.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("Serial port is still closing; restart DataExplorer before another acquisition");
                try { closing.join(Math.max(1, remaining / 1_000_000)); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
