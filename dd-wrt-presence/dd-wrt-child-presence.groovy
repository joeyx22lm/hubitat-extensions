/*
 *  DD-WRT Presence Monitor
 *
 */
metadata {
   definition (name: "DD-WRT Child Presence Monitor", namespace: "com.joeyorlando.hubitat.ddwrt", author: "joeyx22lm", importUrl: "https://raw.githubusercontent.com/joeyx22lm/hubitat-extensions/main/dd-wrt-presence/dd-wrt-child-presence.groovy") {
       capability "PresenceSensor"
       capability "Initialize"

       attribute "presence", "bool"
       attribute "last_seen", "string"
       attribute "last_seen_readable", "string"
       attribute "ap_name", "string"

       command "DetectPresence", null
       command "DeleteThisChild", null
   }

   preferences {
       section("Client Device Settings:") {
           input "mac_address", "string", title:"Mac Address of Client to Track", description: "", required: true, displayDuringSetup: true, defaultValue: ""
           input "updateInterval", "number", title:"Number of seconds between scheduled checks", description: "", required: false, displayDuringSetup: true, defaultValue: "300"
           input name: "enableSchedule", type: "bool", title: "Enable Scheduled Check", defaultValue: false
       }
   }
}

// Native Hooks
void installed() {
   log.info("Initializing presence detection")
   initialize()
}

void initialize(){
    if (enableSchedule) {
        runIn(updateInterval.toInteger(), DetectPresence)
    }
}

// Parent Callbacks
void setMacAddress(String mac_address) {
    device.updateSetting("mac_address", [value: "${mac_address}", type: "string"])
}

def setDevicePresent(syncDate, apName) {
    sendEvent(name: "last_seen", value: syncDate.format("yyyy-MM-dd'T'HH:mm:ss"), isStateChange: true)
    sendEvent(name: "last_seen_readable", value: syncDate.format("E, MMM d, yyyy 'at' hh:mma"), isStateChange: true)
    sendEvent(name: "ap_name", value: apName, isStateChange: !apName.equals(device.currentValue("ap_name")))
    sendEvent(name: "present", value: "present", isStateChange: !"present".equals(device.currentValue("present")))
    log.info("Updated last seen datetime for client ${mac_address}");
}

def setDeviceNotPresent() {
    sendEvent(name: "present", value: "not present", isStateChange: !"not present".equals(device.currentValue("present")))
    log.info("Updated presence state for client ${mac_address}");
}

def setDevicePresenceUnknown() {
    sendEvent(name: "present", value: "unknown", isStateChange: !"unknown".equals(device.currentValue("present")))
    log.info("Updated presence state for client ${mac_address}");
}

// Parent Callouts
void DeleteThisChild(){
    parent.FromChildDeleteChild(mac_address)
}

// Main Method
void DetectPresence(){
   try {
       parent.doWirelessSurvey(mac_address)
   } catch (Exception e) {
       log.error("Unable to retrieve presence data. ${e.message}")
   }
   if (enableSchedule) {
       runIn(updateInterval.toInteger(), DetectPresence)
   }
}
