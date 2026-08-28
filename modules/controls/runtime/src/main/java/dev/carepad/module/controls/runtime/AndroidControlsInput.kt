package dev.carepad.module.controls.runtime

import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

class AndroidDeviceCatalog(private val inputManager:InputManager){
    fun candidates():List<DeviceInfo> = CandidateClassifier.candidates(inputManager.inputDeviceIds.asSequence().mapNotNull{inputManager.getInputDevice(it)?.let(::snapshot)}.toList())
    fun byId(id:Int):DeviceInfo?=inputManager.getInputDevice(id)?.let(::snapshot)
    fun snapshot(d:InputDevice):DeviceInfo{
        val presence=d.hasKeys(*KEYS.keys.toIntArray());val keys=buildSet{KEYS.entries.forEachIndexed{i,e->if(presence.getOrElse(i){false})add(e.value)}}
        val ranges=d.motionRanges.asSequence().filter{it.axis in V1_AXES}.map{RangeInfo(it.axis,sources(it.source),it.min,it.max,it.flat,it.fuzz,it.resolution)}.sortedWith(compareBy<RangeInfo>{it.axis}.thenBy{it.source.toString()}).toList()
        return DeviceInfo(d.id,d.descriptor,d.vendorId,d.productId,d.name.orEmpty(),d.controllerNumber,d.isVirtual,if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)d.isExternal else null,sources(d.sources),keys,ranges)
    }
    companion object{
        private val V1_AXES=setOf(MotionEvent.AXIS_X,MotionEvent.AXIS_Y,MotionEvent.AXIS_Z,MotionEvent.AXIS_RZ,MotionEvent.AXIS_RX,MotionEvent.AXIS_RY,MotionEvent.AXIS_HAT_X,MotionEvent.AXIS_HAT_Y)
        private val KEYS=linkedMapOf(KeyEvent.KEYCODE_BUTTON_A to Button.A,KeyEvent.KEYCODE_BUTTON_B to Button.B,KeyEvent.KEYCODE_BUTTON_X to Button.X,KeyEvent.KEYCODE_BUTTON_Y to Button.Y,KeyEvent.KEYCODE_BUTTON_L1 to Button.L1,KeyEvent.KEYCODE_BUTTON_R1 to Button.R1,KeyEvent.KEYCODE_BUTTON_THUMBL to Button.THUMBL,KeyEvent.KEYCODE_BUTTON_THUMBR to Button.THUMBR,KeyEvent.KEYCODE_BUTTON_START to Button.START,KeyEvent.KEYCODE_BUTTON_SELECT to Button.SELECT,KeyEvent.KEYCODE_BUTTON_MODE to Button.MODE,KeyEvent.KEYCODE_DPAD_UP to Button.DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN to Button.DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT to Button.DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT to Button.DPAD_RIGHT)
        internal fun sources(v:Int)=Sources((v and InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD,(v and InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK,(v and InputDevice.SOURCE_DPAD)==InputDevice.SOURCE_DPAD)
    }
}

object AndroidEventMapper{
    fun key(e:KeyEvent):KeySample?{
        val action=when(e.action){KeyEvent.ACTION_DOWN->KeyAction.DOWN;KeyEvent.ACTION_UP->KeyAction.UP;else->return null}
        return KeySample(e.deviceId,AndroidDeviceCatalog.sources(e.source),e.eventTime,e.keyCode,button(e.keyCode),action,e.repeatCount,(e.flags and KeyEvent.FLAG_CANCELED)!=0)
    }
    fun axes(mapping:Mapping)=buildSet{mapping.left.pair?.let{add(it.x);add(it.y)};mapping.right.pair?.let{add(it.x);add(it.y)};mapping.hat?.let{add(it.x);add(it.y)}}
    fun motion(e:MotionEvent,axes:Set<Int>):List<MotionFrame>{if(axes.isEmpty())return emptyList();val source=AndroidDeviceCatalog.sources(e.source);return buildList{for(i in 0 until e.historySize)add(MotionFrame(e.deviceId,source,e.getHistoricalEventTime(i),axes.associateWith{e.getHistoricalAxisValue(it,i)}));add(MotionFrame(e.deviceId,source,e.eventTime,axes.associateWith(e::getAxisValue)))}}
    private fun button(k:Int)=when(k){KeyEvent.KEYCODE_BUTTON_A->Button.A;KeyEvent.KEYCODE_BUTTON_B->Button.B;KeyEvent.KEYCODE_BUTTON_X->Button.X;KeyEvent.KEYCODE_BUTTON_Y->Button.Y;KeyEvent.KEYCODE_BUTTON_L1->Button.L1;KeyEvent.KEYCODE_BUTTON_R1->Button.R1;KeyEvent.KEYCODE_BUTTON_THUMBL->Button.THUMBL;KeyEvent.KEYCODE_BUTTON_THUMBR->Button.THUMBR;KeyEvent.KEYCODE_BUTTON_START->Button.START;KeyEvent.KEYCODE_BUTTON_SELECT->Button.SELECT;KeyEvent.KEYCODE_BUTTON_MODE->Button.MODE;KeyEvent.KEYCODE_DPAD_UP->Button.DPAD_UP;KeyEvent.KEYCODE_DPAD_DOWN->Button.DPAD_DOWN;KeyEvent.KEYCODE_DPAD_LEFT->Button.DPAD_LEFT;KeyEvent.KEYCODE_DPAD_RIGHT->Button.DPAD_RIGHT;else->null}
}
