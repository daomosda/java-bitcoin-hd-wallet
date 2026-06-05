package com.bitcoin.hdwallet.inputcontrol;

import com.bitcoin.hdwallet.core.AppLogger;

/**
 *
 * @author CONALDES
 */

public class SharedMonitor {
    private boolean worker1Busy = false;
    private boolean worker2Busy = false;
    private boolean signalWorker1 = false;
    private boolean signalWorker2 = false;

    // --- Workers Methods ---
    public synchronized void waitForSignal(int workerId) throws InterruptedException {
        while (true) {
            if (workerId == 1 && signalWorker1) {
                signalWorker1 = false; // Consume signal
                // NOTE: We removed worker1Busy = true from here!
                return;
            }
            if (workerId == 2 && signalWorker2) {
                signalWorker2 = false; // Consume signal
                // NOTE: We removed worker2Busy = true from here!
                return;
            }
            wait(); 
        }
    }

    public synchronized void markWorkerFree(int workerId) {
        if (workerId == 1) worker1Busy = false;
        if (workerId == 2) worker2Busy = false;
        notifyAll(); 
    }

    // --- Controller Methods ---
    public synchronized boolean isAnyWorkerBusy() {
        return worker1Busy || worker2Busy;
    }

    public synchronized void assignTaskTo(int workerId) {
        AppLogger.info("Controller (NODEMHDWallet): Assigning task to Syncer. Id = {} ", workerId);
        
        // THE FIX: Controller marks them busy immediately upon assignment
        if (workerId == 1) {
            worker1Busy = true;
            signalWorker1 = true;
        } else {
            worker2Busy = true;
            signalWorker2 = true;
        }
        
        notifyAll(); // Wake up the workers
    }
}