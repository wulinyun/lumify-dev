package io.lumify.core.model.notification;

import java.util.Date;

public interface UserNotification extends Notification {

    public void setUserId(String userId);

    public String getUserId();

    public void setSentDate(Date startDate);

    public Date getSentDate();

    public void setExpirationAge(ExpirationAge expirationAge);

    public ExpirationAge getExpirationAge();

    public void setMarkedRead(Boolean markedRead);

    public Boolean isMarkedRead();
}
