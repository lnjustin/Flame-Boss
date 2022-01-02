/**
 *  Flame Boss Probe
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
 *a
 *  Change History:
 * v1.0.0 - iniital release
**/

import groovy.json.*
import groovy.json.JsonBuilder

metadata
{
    definition(name: "Flameboss Probe", namespace: "lnjustin", author: "Justin Leonard", importUrl: "")
    {
        capability "Switch"
        capability "TemperatureMeasurement"
        capability "PushableButton"  // for meat done alarm
        
        attribute "meatDoneTemp", "number"
        attribute "meatKeepWarmTemp", "number"
        attribute "meatAlarm", "enum", ["Off", "On", "Keep Warm"]

        command "setMeatDoneTemp", ["NUMBER"]
        command "setMeatKeepWarmTemp", ["NUMBER"]
        command "setMeatAlarm", [[name:"alarm*:",type:"ENUM",desciption:"Meat Alarm", constraints:["Off", "On", "Keep Warm"]]]
    }
}

def setMeatDoneTemp(doneTemp) {
    parent.setMeat(device.getDeviceNetworkId(), device.currentValue("meatAlarm"), doneTemp, device.currentValue("meatKeepWarmTemp"))    
}

def setMeatKeepWarmTemp(keepWarmTemp) {
    parent.setMeat(device.getDeviceNetworkId(), device.currentValue("meatAlarm"), device.currentValue("meatDoneTemp"), keepWarmTemp)    
}

def setMeatAlarm(alarm) {
    log.debug("setting meat alarm to ${alarm}")
    parent.setMeat(device.getDeviceNetworkId(), alarm, device.currentValue("meatDoneTemp"), device.currentValue("meatKeepWarmTemp"))    
}
    
def turnOffDevice() {
    off()   
    sendEvent(name: "temperature", value: "Device Offline", isStateChange: true)
    sendEvent(name: "meatDoneTemp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "meatKeepWarmTemp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "meatAlarm", value: "Device Offline", isStateChange: true)
}

def turnOnDevice() {
    on()     
    if (currentValue("temperature") == "Device Offline" || currentValue("temperature") == null) sendEvent(name: "temperature", value: "No Data", isStateChange: true)
    if (currentValue("meatDoneTemp") == "Device Offline" || currentValue("meatDoneTemp") == null) sendEvent(name: "meatDoneTemp", value: "No Data", isStateChange: true)
    if (currentValue("meatKeepWarmTemp") == "Device Offline" || currentValue("meatKeepWarmTemp") == null) sendEvent(name: "meatKeepWarmTemp", value: "No Data", isStateChange: true)
    if (currentValue("meatAlarm") == "Device Offline" || currentValue("meatAlarm") == null) sendEvent(name: "meatAlarm", value: "No Data", isStateChange: true)
}

def on() {
    sendEvent(name: "switch", value: "on")
}

def off() {
    sendEvent(name: "switch", value: "off")
}

def push(buttonNumber) {
    sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
}
