package dev.carepad.fixture.controlsinput

import android.app.Activity
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.carepad.module.controls.runtime.*
import dev.carepad.module.controls.runtime.Button as ControlButton
import java.util.Locale

class ControlsInputLabActivity:Activity(),InputManager.InputDeviceListener{
    private lateinit var input:InputManager;private lateinit var catalog:AndroidDeviceCatalog;private lateinit var devices:LinearLayout;private lateinit var output:TextView;private var session:ControlsSession?=null
    override fun onCreate(state:Bundle?){super.onCreate(state);input=getSystemService(InputManager::class.java);catalog=AndroidDeviceCatalog(input);setContentView(content());refresh();render()}
    override fun onStart(){super.onStart();input.registerInputDeviceListener(this,null);refresh();render()}
    override fun onStop(){session?.interrupt();input.unregisterInputDeviceListener(this);super.onStop()}
    override fun dispatchKeyEvent(e:KeyEvent):Boolean{val s=session?:return super.dispatchKeyEvent(e);val sample=AndroidEventMapper.key(e)?:return super.dispatchKeyEvent(e);val r=s.acceptKey(sample);if(r.changed)render();return if(r.consumeInTestMode)true else super.dispatchKeyEvent(e)}
    override fun dispatchGenericMotionEvent(e:MotionEvent):Boolean{val s=session?:return super.dispatchGenericMotionEvent(e);if(e.deviceId!=s.device.deviceId)return super.dispatchGenericMotionEvent(e);val frames=AndroidEventMapper.motion(e,AndroidEventMapper.axes(s.mapping));var consume=false;var changed=false;frames.forEach{val r=s.acceptMotion(it);consume=consume||r.consumeInTestMode;changed=changed||r.changed};if(changed)render();return if(consume)true else super.dispatchGenericMotionEvent(e)}
    override fun onInputDeviceAdded(id:Int){refresh()};override fun onInputDeviceRemoved(id:Int){session?.onRemoved(id);refresh();render()};override fun onInputDeviceChanged(id:Int){session?.onChanged(id);refresh();render()}
    private fun content():ScrollView{val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)};root.addView(TextView(this).apply{text="Controls Input Lab\nRead-only prototype. Select exactly one logical deviceId.";textSize=20f});root.addView(TextView(this).apply{text="Consumes control events delivered to this Activity while the test is active. It does not claim to intercept HOME or other Android-reserved keys.";textSize=14f});root.addView(Button(this).apply{text="Refresh input devices";setOnClickListener{refresh()}});devices=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(devices,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));output=TextView(this).apply{textSize=13f;setTextIsSelectable(true)};root.addView(output);return ScrollView(this).apply{addView(root)}}
    private fun refresh(){if(!::devices.isInitialized)return;devices.removeAllViews();val list=catalog.candidates();if(list.isEmpty()){devices.addView(TextView(this).apply{text="No physical control candidates visible."});return};list.forEach{d->devices.addView(Button(this).apply{text="Select deviceId=${d.deviceId} | ${d.name}\nvid:pid=${d.vendorId}:${d.productId} | controller=${d.controllerNumber} | sources=${d.sources} | external=${d.external?:"unknown"}";isAllCaps=false;setOnClickListener{catalog.byId(d.deviceId)?.let{session=ControlsSession(it)};render()}})}}
    private fun render(){if(!::output.isInitialized)return;val s=session;output.text=if(s==null)"No device selected." else summary(s)}
    private fun summary(s:ControlsSession):String{val l=s.leftMetrics();val r=s.rightMetrics();return buildString{appendLine("Selected deviceId=${s.device.deviceId} | ${s.device.name}");appendLine("Session=${s.state} | issues=${if(s.issues.isEmpty())"none" else s.issues}");appendLine("Mapping left=${s.mapping.left.state}, right=${s.mapping.right.state}${if(s.mapping.right.guidedConfirmation)" (guided confirmation required)" else ""}, dpad=${s.mapping.dpad}, issues=${s.mapping.issues}");appendLine("Raw keys=${s.rawKeys.size} | raw motion frames=${s.rawMotion.size}");appendLine("D-pad=${s.dpadPath.lastOrNull()?.directions?:"neutral / no sample"}");ControlButton.values().asList().filter{!it.name.startsWith("DPAD_")}.forEach{val m=s.buttonMetrics(it);if(m.presses>0||m.releases>0||m.pressed)appendLine("$it down=${m.presses} up=${m.releases} pressed=${m.pressed}")};appendLine("Left X ${fmt(l.x)}");appendLine("Left Y ${fmt(l.y)}");appendLine("Left trajectory=${l.trajectory.size}");appendLine("Right X ${fmt(r.x)}");appendLine("Right Y ${fmt(r.y)}");appendLine("Right trajectory=${r.trajectory.size}");appendLine("No drift or insufficient-travel verdict is produced by this prototype.")}}
    private fun fmt(m:AxisMetrics?)=m?.let{String.format(Locale.US,"n=%d duration=%dms median=%.4f mean=%.4f MAD=%.4f min=%.4f max=%.4f maxDev=%.4f coverage=%s",it.count,it.durationMs,it.median,it.mean,it.mad,it.min,it.max,it.maxDeviation,it.coverage?.let{c->"%.3f".format(Locale.US,c)}?:"n/a")}?:"no samples / no conclusion"
}
