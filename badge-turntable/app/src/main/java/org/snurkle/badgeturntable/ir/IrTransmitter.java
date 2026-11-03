package org.snurkle.badgeturntable.ir;

import android.content.Context;
import android.hardware.ConsumerIrManager;

/** Thin wrapper over the phone's IR blaster. transmit() blocks until the code is sent. */
public final class IrTransmitter {
    private final ConsumerIrManager ir;

    public IrTransmitter(Context context) {
        ir = context.getSystemService(ConsumerIrManager.class);
    }

    public boolean available() {
        return ir != null && ir.hasIrEmitter();
    }

    public boolean supportsCarrier(int hz) {
        if (!available()) return false;
        ConsumerIrManager.CarrierFrequencyRange[] ranges = ir.getCarrierFrequencies();
        if (ranges == null) return true;  // some phones don't report ranges; assume yes
        for (ConsumerIrManager.CarrierFrequencyRange r : ranges) {
            if (hz >= r.getMinFrequency() && hz <= r.getMaxFrequency()) return true;
        }
        return false;
    }

    public void send(IrCode code) {
        if (!available()) throw new IllegalStateException("This phone has no IR blaster");
        ir.transmit(code.carrierHz, code.pattern());
    }

    public String describe() {
        if (ir == null || !ir.hasIrEmitter()) return "IR blaster: none";
        StringBuilder sb = new StringBuilder("IR blaster: yes, carriers");
        ConsumerIrManager.CarrierFrequencyRange[] ranges = ir.getCarrierFrequencies();
        if (ranges == null) return sb.append(" not reported").toString();
        for (ConsumerIrManager.CarrierFrequencyRange r : ranges) {
            sb.append(' ').append(r.getMinFrequency()).append('-').append(r.getMaxFrequency()).append(" Hz");
        }
        return sb.toString();
    }
}
