package io.lumify.core;

import com.altamiracorp.bigtable.model.ModelSession;
import io.lumify.core.user.User;
import io.lumify.core.util.ModelUtil;
import com.google.inject.Injector;

import static com.google.common.base.Preconditions.checkNotNull;

public class FrameworkUtils {

    public static void initializeFramework(final Injector injector, final User user) {
        checkNotNull(injector);
        checkNotNull(user);

        final ModelSession modelSession = injector.getInstance(ModelSession.class);

        ModelUtil.initializeTables(modelSession, user);
    }
}
