/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

import java.awt.datatransfer.Transferable;

/**
 *
 * @author tonikelope
 */
public interface ClipboardChangeObservable {

    void attachObserver(ClipboardChangeObserver observer);

    void detachObserver(ClipboardChangeObserver observer);

    Transferable getContents();

    void notifyChangeToMyObservers();

}
