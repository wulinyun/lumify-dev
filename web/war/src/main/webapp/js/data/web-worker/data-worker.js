var BASE_URL = '../../..',
    self = this,
    cacheBreaker = null,
    publicData = {};

onmessage = function(event) {
    if (!cacheBreaker) {
        cacheBreaker = event.data;
        setupAll();
        return;
    }

    require([
        'underscore',
        'util/promise',
        'data/web-worker/util/store'
    ], function(_, Promise, store) {
        self.store = store;
        onMessageHandler(event);
    })
};

function setupAll() {
    setupConsole();
    setupWebsocket();
    setupRequireJs();
}

function setupConsole() {
    var noop = function() {};

    if (typeof console === 'undefined') {
        console = {
            log: log('log'),
            info: log('info'),
            debug: log('debug'),
            error: log('error'),
            warn: log('warn'),
            group: noop,
            groupCollapsed: noop,
            groupEnd: noop
        };
    }
    function log(type) {
        return function() {
            dispatchMain('brokenWorkerConsole', {
                logType: type,
                messages: Array.prototype.slice.call(arguments, 0).map(function(arg) {
                    return JSON.stringify(arg);
                })
            });
        }
    }
}

function setupWebsocket() {
    var supportedInWorker = !!(this.WebSocket || this.MozWebSocket);

    if (supportedInWorker) {
        self.window = self;
        importScripts(BASE_URL + '/libs/atmosphere/atmosphere.js?' + cacheBreaker);
        atmosphere.util.getAbsoluteURL = function() {
            return location.origin +
                location.pathname.replace(/\/jsc.*$/, '') +
                '/messaging';
        }
        self.pushSocketMessage = function(message) {
            Promise.all([
                Promise.require('util/websocket'),
                new Promise(function(fulfill, reject) {
                    if (atmosphere.util.__socketOpened) {
                        fulfill(publicData.socket);
                    }
                    atmosphere.util.__socketPromiseFulfill = fulfill;
                    atmosphere.util.__socketPromiseReject = reject;
                })
            ]).done(function(results) {
                var websocketUtils = results[0],
                    socket = results[1];

                websocketUtils.pushDataToSocket(socket, publicData.socketSourceGuid, message);
            });
        }
    } else {
        dispatchMain('websocketNotSupportedInWorker');
        self.pushSocketMessage = function(message) {
            dispatchMain('websocketFromWorker', { message: message });
        }
    }
}

function setupRequireJs() {
    if (typeof FormData === 'undefined') {
        importScripts('./util/formDataPolyfill.js?' + cacheBreaker);
    }
    importScripts(BASE_URL + '/jsc/require.config.js?' + cacheBreaker);
    require.baseUrl = BASE_URL + '/jsc/';
    require.urlArgs = cacheBreaker;
    importScripts(BASE_URL + '/libs/requirejs/require.js?' + cacheBreaker);
}

function onMessageHandler(event) {
    var data = event.data;
    processMainMessage(data);
}

function processMainMessage(data) {
    if (data.type) {
        require(['data/web-worker/handlers/' + data.type], function(handler) {
            handler(data);
        });
    } else console.warn('Unhandled message to worker', event);
}

function dispatchMain(type, message) {
    if (!type) {
        throw new Error('dispatchMain requires type argument');
    }
    message = message || {};
    message.type = type;
    try {
        postMessage(message);
    } catch(e) {
        var jsonString = JSON.stringify(message);
        postMessage({
            type:'brokenWorkerConsole',
            logType: 'error',
            messages: ['error posting', e.message, jsonString]
        });
    }
}

function ajaxPrefilter(xmlHttpRequest, method, url, parameters) {
    if (publicData) {
        var filters = [
                setWorkspaceHeader,
                setCsrfHeader,
                // TODO: set timezone
            ], invoke = function(f) {
                f();
            };

        filters.forEach(invoke);
    }

    function setWorkspaceHeader() {
        var hasWorkspaceParam = typeof (parameters && parameters.workspaceId) !== 'undefined';
        if (publicData.currentWorkspaceId && !hasWorkspaceParam) {
            xmlHttpRequest.setRequestHeader('Lumify-Workspace-Id', publicData.currentWorkspaceId);
        }
    }
    function setCsrfHeader() {
        var eligibleForProtection = !(/get/i).test(method),
            user = publicData.currentUser,
            token = user && user.csrfToken;

        if (eligibleForProtection && token) {
            xmlHttpRequest.setRequestHeader('Lumify-CSRF-Token', token);
        }
    }
}

function ajaxPostfilter(xmlHttpRequest, jsonResponse, request) {
    if (!jsonResponse) {
        return;
    }

    var params = request.parameters,
        workspaceId = params && params.workspaceId;

    if (!workspaceId) {
        workspaceId = publicData.currentWorkspaceId;
    }

    store.checkAjaxForPossibleCaching(xmlHttpRequest, jsonResponse, workspaceId, request);
}
