package com.example.jellyfinserver.core

enum class ServerState {
    STOPPED,
    STARTING,
    PROCESS_STARTED,
    RUNTIME_INITIALIZED,
    JELLYFIN_INITIALIZING,
    HTTP_WAITING,
    TCP_CHECK,
    HTTP_CHECK,
    RUNNING,
    STOPPING,
    
    // Detailed intermediate/error states
    WEB_STATIC_ONLY,
    API_NOT_READY,
    
    // Failure states
    START_FAILED,
    PROCESS_EXITED,
    TCP_BIND_FAILED,
    HTTP_NOT_READY
}
