package com.lokesh.spectrum;

import android.service.notification.NotificationListenerService;

/**
 * Empty listener. Android only lets an app read which song is playing (media sessions)
 * when the user has granted it notification access, which requires this service to exist.
 */
public class NowPlayingListener extends NotificationListenerService {
}
