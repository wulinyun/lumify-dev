package io.lumify.core.model.workspace;

import java.io.Serializable;

public interface Workspace extends Serializable {
    public static final long serialVersionUID = 1L;

    String getWorkspaceId();

    String getDisplayTitle();
}

