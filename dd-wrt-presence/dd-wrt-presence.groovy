/*
 *  DD-WRT Presence Monitor
 *
 */
metadata {
   definition (
      name: "DD-WRT Presence Monitor",
      namespace: "com.joeyorlando.hubitat.ddwrt",
      author: "joeyx22lm",
      importUrl: "https://raw.githubusercontent.com/joeyx22lm/hubitat-extensions/main/dd-wrt-presence/dd-wrt-presence.groovy"
   ) {
      attribute "last_sync", "string"
      attribute "last_sync_readable", "string"
      attribute "status", "string"
      attribute "error_message", "string"
      attribute "wireless_survey", "string"
      command "CreateChildPresence", ["MAC Address","Device Label"]
      command "ClearWirelessSurvey", null
   }

   preferences {
       section("DD-WRT Settings:") {
           input "scheme", "string", title:"HTTP Scheme", description: "Either 'http' or 'https'", required: true, displayDuringSetup: true, defaultValue: "https"
           input "hostname", "string", title:"Hostname", description: "Hostname or IP address of DD-WRT router", required: true, displayDuringSetup: true, defaultValue: ""
           input "username", "string", title:"Username", description: "Username for DD-WRT router", required: true, displayDuringSetup: true, defaultValue: ""
           input "password", "string", title:"Password", description: "Password for DD-WRT router", required: true, displayDuringSetup: true, defaultValue: ""
           input name: "ignoreSSLIssues", type: "bool", title: "Ignore SSL Certificate issues (required if schema = 'https')", required: true, defaultValue: true
       }
   }
}

def CreateChildPresence(String mac_address, String label){
    String parentId = device.id
    def childDevice = getChildDevice("${parentId}-${mac_address}")
    if (!childDevice) {
        childDevice = addChildDevice("DD-WRT Child Presence Monitor", "${parentId}-${mac_address}", [name: "${label}", isComponent: true])
    }
    childDevice.setMacAddress(mac_address)
    return cd
}

def ClearWirelessSurvey() {
    sendEvent(name: "wireless_survey", value: null, isStateChange: device.currentValue("wireless_survey") != null)
}

// Child Callbacks
void FromChildDeleteChild(String mac_address){
    String parentId = device.id
    log.warn("Deleting Child Monitor: ${parentId}-${mac_address}")
    deleteChildDevice("${parentId}-${mac_address}")
}


// Helper Methods
void reportSync(syncDateTime) {
    sendEvent(name: "last_sync", value: syncDateTime.format("yyyy-MM-dd'T'HH:mm:ss"), isStateChange: true)
    sendEvent(name: "last_sync_readable", value: syncDateTime.format("E, MMM d, yyyy 'at' hh:mma"), isStateChange: true)
    sendEvent(name: "status", value: "Connected", isStateChange: !"Connected".equals(device.currentValue("status")))
    sendEvent(name: "error_message", value: null, isStateChange: device.currentValue("error_message") != null)
}

void reportError(String error) {
    sendEvent(name: "status", value: "Error", isStateChange: !"Error".equals(device.currentValue("status")))
    sendEvent(name: "error_message", value: null, isStateChange: !error.equals(device.currentValue("error_message")))
}

def getServerUrl() {
    return "${scheme}://${hostname}";
}

def getAuthToken() {
    return "${username}:${password}".bytes.encodeBase64();
}

// DD-WRT Communication
void doWirelessSurvey(mac_address) {
    // Check whether wireless survey has been conducted
    def wirelessSurvey = device.currentValue("wireless_survey")
    if (wirelessSurvey != null) {
        def jsonSlurper = new groovy.json.JsonSlurper()
        syncWirelessActivityStatus(jsonSlurper.parseText(wirelessSurvey), mac_address)
        return;
    }

    try {
        asynchttpGet('handleWirelessSurvey', [
            uri: "${getServerUrl()}/Wireless_Basic.asp",
            headers: [
              'Accept': 'text/html',
              'Authorization': "Basic ${getAuthToken()}"
            ],
            timeout: 60,
            ignoreSSLIssues: ignoreSSLIssues
        ], [
            mac_address: mac_address
        ])
    } catch(Exception e) {
        if(e.message.toString() != "OK") {
            String errorMessage = "Unable to fetch wireless survey from ${getServerUrl()}/Wireless_Basic.asp - ${e.message}"
            log.error(errorMessage)
            reportError(errorMessage);
            syncARPClients(mac_address);
        }
    }
}

def handleWirelessSurvey(response, data) {
    if(response.hasError()) {
        String errorMessage = "Received error while fetching wireless survey from ${getServerUrl()}/Wireless_Basic.asp: ${response.getErrorMessage()} (status: ${response.getStatus()})"
        log.error(errorMessage)
        reportError(errorMessage);
        syncARPClients(data.mac_address);
        return;
    }

    if(response.getStatus() != 200) {
        String errorMessage = "Received unexpected status code while fetching wireless survey from ${getServerUrl()}/Wireless_Basic.asp: ${response.getStatus()}"
        log.error(errorMessage)
        reportError(errorMessage);
        syncARPClients(data.mac_address);
        return;
    }

    def wirelessSurvey = [:]
    boolean retrievedWirelessSurvey = false;
    String body = response.getData();
    def matcher = body =~ /.*(wl\d+) \- SSID \[([^\]]+)\] HWAddr.*/
    for (int i = 0; i < matcher.size(); i++) {
        retrievedWirelessSurvey = true;
        String deviceId = matcher[i][0];
        String ssid = matcher[i][1];
        wirelessSurvey[deviceId] = ssid;
    }

    if (!retrievedWirelessSurvey) {
        String errorMessage = "Unable to parse response while performing wireless survey."
        log.error(errorMessage)
        reportError(errorMessage);
        syncARPClients(data.mac_address);
        return;
    }

    sendEvent(name: "wireless_survey", value: groovy.json.JsonOutput.toJson(wirelessSurvey), isStateChange: true)
    syncWirelessActivityStatus(wirelessSurvey, data.mac_address);
}

void syncWirelessActivityStatus(wirelessSurvey, mac_address) {
    try {
        asynchttpGet('handleWirelessActivityStatus', [
            uri: "${getServerUrl()}/Status_Wireless.live.asp",
            headers: [
              'Accept': '*/*',
              'Authorization': "Basic ${getAuthToken()}"
            ],
            timeout: 60,
            ignoreSSLIssues: ignoreSSLIssues
        ], [
            wirelessSurvey: wirelessSurvey,
            mac_address: mac_address
        ])
    } catch(Exception e) {
        if(e.message.toString() != "OK") {
            String errorMessage = "Unable to fetch active wireless clients from ${getServerUrl()}/Status_Wireless.live.asp - ${e.message}"
            log.error(errorMessage)
            reportError(errorMessage);
            syncARPClients(mac_address);
        }
    }
}

def handleWirelessActivityStatus(response, data) {
    if(response.hasError()) {
        String errorMessage = "Received error while fetching active wireless clients from ${getServerUrl()}/Status_Wireless.live.asp: ${response.getErrorMessage()} (status: ${response.getStatus()})"
        log.error(errorMessage)
        reportError(errorMessage);
        syncARPClients(data.mac_address);
        return;
    }

    if(response.getStatus() != 200) {
        String errorMessage = "Received unexpected status code while fetching active wireless clients from ${getServerUrl()}/Status_Wireless.live.asp: ${response.getStatus()}"
        log.error(errorMessage)
        reportError(errorMessage);
        syncARPClients(data.mac_address);
        return;
    }

    boolean retrievedClientList = false;
    String foundDeviceId = null;
    String body = response.getData();
    String[] bodyData = body.split("\\}\\{");
    for (String dataEntry : bodyData) {
        if (dataEntry.startsWith("active_wireless")) {
            retrievedClientList = true;
            def matcher = dataEntry =~ /(([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2}))\'\,\'([a-zA-Z\d]+)\'/
            for (int i = 0; i < matcher.size(); i++) {
                if (matcher[i].size() > 4) {
                    String activeMacAddress = matcher[i][1].trim();
                    String activeDeviceId = matcher[i][4].trim();
                    if (data.mac_address.trim().equalsIgnoreCase(activeMacAddress)) {
                        log.debug("Found client ${data.mac_address} in list of active wireless clients on device ${activeDeviceId}");
                        // Found wireless client with the given MAC address.
                        foundDeviceId = activeDeviceId;
                        break;
                    }
                }
            }
        }
    }

    if (!retrievedClientList) {
        log.error("Unable to parse response while fetching active wireless clients. Body: ${body}");
        syncARPClients(data.mac_address);
        return;
    }

    if (foundDeviceId == null) {
        log.debug("Client ${data.mac_address} was not found in list of wireless clients");
        syncARPClients(data.mac_address);
        return;
    }

    log.debug("Client ${data.mac_address} was found connected to device ${foundDeviceId} (SSID: ${data.wirelessSurvey[foundDeviceId]})");
    def currentDateTime = new Date();
    log.info("Retrieving child device: ${device.id}-${data.mac_address}")
    def childDevice = getChildDevice("${device.id}-${data.mac_address}");
    reportSync(currentDateTime);
    childDevice.setDevicePresent(currentDateTime, data.wirelessSurvey[foundDeviceId])
}

void syncARPClients(mac_address) {
    try {
        asynchttpGet('handleARPClients', [
            uri: "${getServerUrl()}/Status_Lan.live.asp",
            headers: [
              'Accept': '*/*',
              'Authorization': "Basic ${getAuthToken()}"
            ],
            timeout: 60,
            ignoreSSLIssues: ignoreSSLIssues
        ], [
            mac_address: mac_address
        ])
    } catch(Exception e) {
        if(e.message.toString() != "OK") {
            String errorMessage = "Unable to fetch ARP clients from ${getServerUrl()}/Status_Lan.live.asp - ${e.message}"
            log.error(errorMessage)
            reportError(errorMessage);
            childDevice.setDevicePresenceUnknown()
        }
    }
}

def handleARPClients(response, data) {
    log.info("Retrieving child device: ${device.id}-${data.mac_address}")
    def childDevice = getChildDevice("${device.id}-${data.mac_address}");
    if(response.hasError()) {
        String errorMessage = "Received error while fetching ARP clients from ${getServerUrl()}/Status_Lan.live.asp: ${response.getErrorMessage()} (status: ${response.getStatus()})";
        log.error(errorMessage)
        reportError(errorMessage);
        childDevice.setDevicePresenceUnknown()
        return;
    }

    if(response.getStatus() != 200) {
        String errorMessage = "Received unexpected status code while fetching ARP clients from ${getServerUrl()}/Status_Lan.live.asp: ${response.getStatus()}";
        log.error(errorMessage)
        reportError(errorMessage);
        childDevice.setDevicePresenceUnknown()
        return;
    }

    def currentDateTime = new Date();
    String body = response.getData();
    if (body.toUpperCase().contains(data.mac_address.toUpperCase().trim())) {
        childDevice.setDevicePresent(currentDateTime, "WIRED")
    } else {
        childDevice.setDeviceNotPresent()
    }
    reportSync(currentDateTime);
}
