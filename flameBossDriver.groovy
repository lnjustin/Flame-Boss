/**
 *  Flame Boss Driver
 *
 *  Copyright\u00A9 2021 Justin Leonard
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Change History:
 * v1.0.0 - iniital release
**/

import groovy.json.*
import groovy.json.JsonBuilder

metadata
{
    definition(name: "Flameboss", namespace: "lnjustin", author: "Justin Leonard", importUrl: "")
    {
        capability "Refresh"
        capability "Initialize"
        capability "ContactSensor"
        capability "Switch"
        capability "TemperatureMeasurement"  // for pit temp
        capability "PushableButton"  // for pit alarm
        
        attribute "driverStatus", "string"
        
        attribute "pitTempTarget", "number"
        attribute "pitAlarmTemp", "number"
        attribute "pitAlarmEnabled", "string"
        
        attribute "fanSpeed", "number"
        
        command "setPitTargetTemp", ["number"]
        command "setPitAlarm",[[name:"enabled*",type:"ENUM",description:"Alarm On/Off", constraints:["Enabled", "Disabled"]],
                               [name:"pitAlarmTemp*:",type:"NUMBER",desciption:"Pit Alarm Temp"]]        
    }
}

preferences
{
    section
    {
        
        input name: "deviceID", type: "String", title: "Flameboss Device ID", required: true
        input name: "flamebossServer", type: "String", title: "Flameboss Server Name", required: true
        input name: "userID", type: "String", title: "Flameboss UserID", required: true
        input name: "authToken", type: "String", title: "Auth Token", required: true
        input name: "units", type: "enum", options:["Celcius", "Fahrenheit"], title: "Units", defaultValue: "Fahrenheit"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() {
    logDebug("installed()")
    
    initialize()
    runEvery1Hour(initialize)
}

def updated()
{
    configure()
}

def configure()
{    
    refresh()
}

def initialize() {
    logDebug("initialize()")
    device.updateSetting("numberOfButtons", 2)  // button 1 is for pit alarm; button 2 is for vent advice
    createChildren()
    if (interfaces.mqtt.isConnected()) {
        logDebug("Disconnecting...")
        interfaces.mqtt.disconnect()
    } 
    turnOffDevices()
    if (deviceID != null && flamebossServer != null && userID != null && authToken != null) runIn(1, connectToMqtt)
    else log.error "Missing credentials for Flameboss. Input credentials in the Preferences section of the device."
}

def getFlamebossProbeID(probeNum) {
    return "FlamebossProbe${probeNum}"    
}

def getFlamebossProbeName(probeNum) {
    return "Probe ${probeNum}"
}

def createChildren() {
    def probe1Child = getChildDevice(getFlamebossProbeID(1))  
    def probe2Child = getChildDevice(getFlamebossProbeID(2)) 
    def probe3Child = getChildDevice(getFlamebossProbeID(3)) 
    
    if (probe1Child == null) {
        probe1Child = addChildDevice("lnjustin", "Flameboss Probe", getFlamebossProbeID(1), [label:getFlamebossProbeName(1), isComponent:true, name:getFlamebossProbeName(1)])
        if (probe1Child != null) {
            probe1Child.turnOffDevice()
            probe1Child.updateSetting("numberOfButtons", 1)
        }
    }
    if (probe2Child == null) {
        probe2Child = addChildDevice("lnjustin", "Flameboss Probe", getFlamebossProbeID(2), [label:getFlamebossProbeName(2), isComponent:true, name:getFlamebossProbeName(2)])
        if (probe2Child != null) {
            probe2Child.turnOffDevice()
            probe2Child.updateSetting("numberOfButtons", 1)
        }
    }
    if (probe3Child == null) {
        probe3Child = addChildDevice("lnjustin", "Flameboss Probe", getFlamebossProbeID(3), [label:getFlamebossProbeName(3), isComponent:true, name:getFlamebossProbeName(3)])
        if (probe3Child != null) {
            probe3Child.turnOffDevice()
            probe3Child.updateSetting("numberOfButtons", 1)
        }
    }
}

def uninstalled() {
    interfaces.mqtt.disconnect()
    deleteChildren()
}

def deleteChildren()
{
    for(child in getChildDevices())
    {
        deleteChildDevice(child.deviceNetworkId)
    }
}

def parse(String description) {

        def decoded = interfaces.mqtt.parseMessage(description)
        log.debug "parse(${decoded})"
    
        if (decoded.topic == topicNameAttribute("open")) {
            def jsonSlurper = new JsonSlurper()
            def payload = jsonSlurper.parseText(decoded.payload)
            logDebug("Received JSON payload: ${payload}")
            if (payload.name == "protocol") {
                turnOnDevices()
            }
            if (payload.temps  != null) {
                def pitTemp = convertReceivedUnits(payload.temps[0])
                sendEvent(name: "temperature", value: pitTemp, isStateChange: true)
                
                def probe1Temp = (payload.temps[1] != nulll) ? convertReceivedUnits(payload.temps[1]) : null
                def probe2Temp = (payload.temps[2] != nulll) ? convertReceivedUnits(payload.temps[2]) : null
                def probe3Temp = (payload.temps[3] != nulll) ? convertReceivedUnits(payload.temps[3]) : null
                
                def probe1Child = getChildDevice(getFlamebossProbeID(1))  
                def probe2Child = getChildDevice(getFlamebossProbeID(2)) 
                def probe3Child = getChildDevice(getFlamebossProbeID(3)) 
                
                if (probe1Child) probe1Child.sendEvent(name: "temperature", value: probe1Temp, isStateChange: true)
                if (probe2Child) probe2Child.sendEvent(name: "temperature", value: probe2Temp, isStateChange: true)
                if (probe3Child) probe3Child.sendEvent(name: "temperature", value: probe3Temp, isStateChange: true)
            }
            if (payload.set_temp != null) {
                def targetPitTemp = convertReceivedUnits(payload.set_temp)
                sendEvent(name: "pitTempTarget", value: targetPitTemp, isStateChange: true)
            }
            if (payload.blower != null) {            
                def fanSpeed = payload.blower
                sendEvent(name: "fanSpeed", value: fanSpeed, isStateChange: true)
            }

        }
        else if (decoded.topic == topicNameAttribute("data")) {
            def jsonSlurper = new JsonSlurper()
            def payload = jsonSlurper.parseText(decoded.payload)

            if (payload.name == "meat_alarm") {
                if (payload.sensor == 1 || payload.sensor == 2 || payload.sensor == 3) {
                    def probeChild = getChildDevice(getFlamebossProbeID(payload.sensor)) 
                    if (probeChild) {
                        probeChild.sendEvent(name: "meatDoneTemp", value: convertReceivedUnits(payload.done_temp), isStateChange: true)
                        probeChild.sendEvent(name: "meatKeepWarmTemp", value: convertReceivedUnits(payload.warm_temp), isStateChange: true)
                        probeChild.sendEvent(name: "meatAlarm", value: getMeatActionFriendly(payload.action), isStateChange: true)
                    }
                }
            }
            else if (payload.name == "pit_alarm") {
                if (payload.enabled != null) sendEvent(name: "pitAlarmEnabled", value: payload.enabled, isStateChange: true)
                if (payload.range != null) sendEvent(name: "pitAlarmTemp", value: convertReceivedUnits(payload.range), isStateChange: true)
            }
            else if (payload.name == "meat_alarm_triggered") {
                def probeChild = getChildDevice(getFlamebossProbeID(payload.sensor)) 
                if (probeChild) probeChild.push(1)               
            }
            else if (payload.name == "pit_alarm_triggered") {
                push(1)               
            }
            else if (payload.name == "vent_advice") {
                push(2)               
            }            
            else if (payload.name == "opened" || payload.name == "closed") sendEvent(name: "contact", value: payload.name, isStateChange: true, descriptionText: "Cooker is ${payload.name}")
            else if (payload.name == "disconnected") {
                 turnOffDevices()
            }
        }
}

def push(buttonNumber) {
    sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
}

def convertReceivedUnits(value) {
    if (units == "Fahrenheit") {
        return Math.round((value * (9/50)) +32)
        // NOTE: this formula should have 9/5 instead of 9/50 - not sure why this is what makes it work?
    }
    else if (units == "Celcius") {
        return value
    }
}

def convertUnitsToSend(BigDecimal value) {
    if (units == "Fahrenheit") {
       def unitsC = Math.round((value - 32) * (50/9))
        // NOTE: this formula should have 5/9  instead of 50/9 - not sure why this is what makes it work?
        return unitsC
    }
    else if (units == "Celcius") {
        return value.intValueExact() 
    }
}

def setPitTargetTemp(temp) {
    def commandPayload = new JsonOutput().toJson([name:"set_temp", value:"${convertUnitsToSend(temp)}"])
    logDebug("Setting Pit: ${commandPayload}")
    interfaces.mqtt.publish(topicNameCommand(), commandPayload)    
}

def setPitAlarm(enabled, temp) {
    def commandPayload = new JsonOutput().toJson([name:"pit_alarm", enabled: (enabled == "Enabled") ? true : false, range:"${temp}"])
    logDebug("Setting Pit Alarm: ${commandPayload}")
    interfaces.mqtt.publish(topicNameCommand(), commandPayload)    
}

def setMeat(deviceNetworkId, action, doneTemp, warmTemp) {
    def sensorNum = getSensorNum(deviceNetworkId)
    def actionKey = getMeatActionKey(action)
    if (sensorNum >= 1 && sensorNum <= 3) {
        def commandPayload = new JsonOutput().toJson([name:"meat_alarm", sensor:sensorNum, action:actionKey, done_temp: convertUnitsToSend(doneTemp), warm_temp:convertUnitsToSend(warmTemp)])
        logDebug("Setting Meat: ${commandPayload}")
        interfaces.mqtt.publish(topicNameCommand(), commandPayload) 
    }
}

def getMeatActionKey(action) {
    def key = null
    if (action == "Off") key = "off"
    else if (action == "On") key = "on"
    else if (action == "Keep Warm") key = "keep_warm"
    return key
}

def getMeatActionFriendly(actionKey) {
    def friendly = null
    if (actionKey == "off") friendly = "Off"
    else if (actionKey == "on") friendly = "On"
    else if (actionKey == "keep_warm") friendly = "Keep Warm"
    return friendly
}

def getSensorNum(deviceNetworkId) {
    def sensorNum = null
    if (deviceNetworkId == getFlamebossProbeID(1)) sensorNum = 1
    else if (deviceNetworkId == getFlamebossProbeID(2)) sensorNum = 2
    else if (deviceNetworkId == getFlamebossProbeID(3)) sensorNum = 3   
    return sensorNum
}

def mqttClientStatus(String status) {
    logDebug("mqttClientStatus(${status})")
    if (status.take(6) == "Error:") {
        log.error "Connection error..."
        sendEvent(name: "driverStatus", value: "ERROR")
        
        try {
            interfaces.mqtt.disconnect()  // clears buffers
        }
        catch (e) {
        }
        
        logDebug("Attempting to reconnect in 5 seconds...");
        runIn(5, connectToMqtt)
    }
    else {
        logDebug("Connected!")
        sendEvent(name: "driverStatus", value: "Connected")
    }
}


def connectToMqtt() {
    logDebug("connectToMqtt()")
    
    if (!interfaces.mqtt.isConnected()) {        
        logDebug("Connecting to MQTT...")
        interfaces.mqtt.connect("tcp://${flamebossServer}:1883", device.getDeviceNetworkId() + "driver", "T-" + userID, authToken)
        
        runIn(1, subscribe)
    }
}


def subscribe() {
    logDebug("Subscribing...")
    
    // Subscribe to attributes
    interfaces.mqtt.subscribe(topicNameAttribute("open"))
    interfaces.mqtt.subscribe(topicNameAttribute("data"))
    interfaces.mqtt.subscribe(topicNameAttribute("rc"))
    interfaces.mqtt.subscribe(topicNameAttribute("console"))
    interfaces.mqtt.subscribe(topicNameAttribute("adc"))
}
            
def turnOffDevices() {
    off()   
    sendEvent(name: "temperature", value: "Device Offline", isStateChange: true)
    sendEvent(name: "pitTempTarget", value: "Device Offline", isStateChange: true)
    sendEvent(name: "fanSpeed", value: "Device Offline", isStateChange: true)
    sendEvent(name: "pitAlarmEnabled", value: "Device Offline", isStateChange: true)
    sendEvent(name: "pitAlarmTemp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "contact", value: "Device Offline", isStateChange: true)
    
    def probe1Child = getChildDevice(getFlamebossProbeID(1))  
    def probe2Child = getChildDevice(getFlamebossProbeID(2)) 
    def probe3Child = getChildDevice(getFlamebossProbeID(3)) 
                
    if (probe1Child) probe1Child.turnOffDevice()
    if (probe2Child) probe2Child.turnOffDevice()
    if (probe3Child) probe3Child.turnOffDevice()
}

def turnOnDevices() {
    on()   
    if (currentValue("temperature") == "Device Offline" || currentValue("temperature") == null) sendEvent(name: "temperature", value: "No Data", isStateChange: true)
    if (currentValue("fanSpeed") == "Device Offline" || currentValue("fanSpeed") == null) sendEvent(name: "fanSpeed", value: "No Data", isStateChange: true)
    if (currentValue("pitAlarmEnabled") == "Device Offline" || currentValue("pitAlarmEnabled") == null) sendEvent(name: "pitAlarmEnabled", value: "No Data", isStateChange: true)
    if (currentValue("pitAlarmTemp") == "Device Offline" || currentValue("pitAlarmTemp")) sendEvent(name: "pitAlarmTemp", value: "No Data", isStateChange: true)
    if (currentValue("contact") == "Device Offline" || currentValue("contact") == null) sendEvent(name: "contact", value: "No Data", isStateChange: true)

    def probe1Child = getChildDevice(getFlamebossProbeID(1))  
    def probe2Child = getChildDevice(getFlamebossProbeID(2)) 
    def probe3Child = getChildDevice(getFlamebossProbeID(3)) 
                
    if (probe1Child) probe1Child.turnOnDevice()
    if (probe2Child) probe2Child.turnOnDevice()
    if (probe3Child) probe3Child.turnOnDevice()
}

def topicNameCommand() {
    return "flameboss/${deviceID}/recv"   
}

def topicNameAttribute(String attribute) {      
    def topicBase = "flameboss/${deviceID}/send"    
    def topicName = "${topicBase}/${attribute}"
    return topicName
}

def on() {
    sendEvent(name: "switch", value: "on")
}

def off() {
    sendEvent(name: "switch", value: "off")
}


def refresh()
{
    initialize()
}

def logDebug(msg) 
{
    if (logEnable)
    {
        log.debug(msg)
    }
}  
