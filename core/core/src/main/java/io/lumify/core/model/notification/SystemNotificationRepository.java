package io.lumify.core.model.notification;

import io.lumify.core.model.lock.LockRepository;
import io.lumify.core.model.user.UserRepository;
import io.lumify.core.model.workQueue.WorkQueueRepository;
import io.lumify.core.user.User;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import org.apache.commons.lang.time.DateUtils;
import org.json.JSONObject;

import java.util.Date;
import java.util.List;

public abstract class SystemNotificationRepository extends NotificationRepository {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(SystemNotificationRepository.class);
    private static final String LOCK_NAME = SystemNotificationRepository.class.getName();
    private boolean shutdown;

    public abstract List<SystemNotification> getActiveNotifications(User user);

    public abstract List<SystemNotification> getFutureNotifications(Date maxDate, User user);


    public abstract SystemNotification createNotification(SystemNotificationSeverity severity, String title, String message, String actionEvent, JSONObject actionPayload, Date startDate, Date endDate);

    public SystemNotification createNotification(
            SystemNotificationSeverity severity,
            String title,
            String message,
            Date startDate,
            Date endDate
    ) {
        return createNotification(severity, title, message, null, null, startDate, endDate);
    }

    public SystemNotification createNotification(
            SystemNotificationSeverity severity,
            String title,
            String message,
            String externalUrl,
            Date startDate,
            Date endDate
    ) {
        String actionEvent = null;
        JSONObject actionPayload = null;

        if (externalUrl != null) {
            actionEvent = Notification.ACTION_EVENT_EXTERNAL_URL;
            actionPayload = new JSONObject();
            actionPayload.put("url", externalUrl);
        }

        return createNotification(severity, title, message, actionEvent, actionPayload, startDate, endDate);
    }

    public abstract SystemNotification getNotification(String rowKey, User user);

    public abstract SystemNotification updateNotification(SystemNotification notification);

    public abstract void endNotification(SystemNotification notification);

    public static JSONObject toJSONObject(SystemNotification notification) {
        JSONObject json = new JSONObject();
        json.put("id", notification.getId());
        json.put("type", "system");
        json.put("severity", notification.getSeverity().toString());
        json.put("title", notification.getTitle());
        json.put("message", notification.getMessage());
        if (notification.getActionEvent() != null) {
            JSONObject action = new JSONObject();
            action.put("event", notification.getActionEvent());
            action.putOpt("data", notification.getActionPayload());
            json.put("action", action);
        }
        json.put("startDate", notification.getStartDate().getTime());
        json.put("endDate", notification.getEndDate() == null ? null : notification.getEndDate().getTime());
        json.put("hash", hash(json.toString()));
        return json;
    }

    public static boolean isActive(SystemNotification notification) {
        Date now = new Date();
        Date endDate = notification.getEndDate();
        return notification.getStartDate().before(now) && (endDate == null || endDate.after(now));
    }

    // TODO: use LeaderSelector, http://curator.apache.org/curator-recipes/leader-election.html
    protected void startBackgroundThread(final LockRepository lockRepository, final UserRepository userRepository, final WorkQueueRepository workQueueRepository) {
        Runnable acquireLock = new Runnable() {
            @Override
            public void run() {
                Runnable useLock = new Runnable() {
                    @Override
                    public void run() {
                        LOGGER.debug("using successfully acquired lock");
                        runPeriodically(userRepository, workQueueRepository);
                    }
                };
                LOGGER.debug("acquiring lock...");
                lockRepository.lock(LOCK_NAME, useLock);
            }
        };

        LOGGER.debug("starting background thread");
        Thread thread = new Thread(acquireLock);
        thread.setName(this.getClass().getSimpleName() + "-background-thread");
        thread.setDaemon(true);
        thread.start();
    }

    private void runPeriodically(UserRepository userRepository, WorkQueueRepository workQueueRepository) {
        while (!shutdown) {
            LOGGER.debug("running periodically");
            Date now = new Date();
            Date nowPlusOneMinute = DateUtils.addMinutes(now, 1);
            List<SystemNotification> notifications = getFutureNotifications(nowPlusOneMinute, userRepository.getSystemUser());
            for (SystemNotification notification : notifications) {
                workQueueRepository.pushSystemNotification(notification);
            }
            try {
                long remainingMilliseconds = nowPlusOneMinute.getTime() - System.currentTimeMillis();
                if (remainingMilliseconds > 0) {
                    Thread.sleep(remainingMilliseconds);
                }
            } catch (InterruptedException e) {
                // do nothing
            }
        }
    }

    public void shutdown() {
        shutdown = true;
    }
}
