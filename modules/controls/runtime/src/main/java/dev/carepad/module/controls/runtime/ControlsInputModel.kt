package dev.carepad.module.controls.runtime

import kotlin.math.abs

object Axes { const val X=0; const val Y=1; const val Z=11; const val RX=12; const val RY=13; const val RZ=14; const val HAT_X=15; const val HAT_Y=16 }
data class Sources(val gamepad:Boolean=false,val joystick:Boolean=false,val dpad:Boolean=false){ val control get()=gamepad||joystick||dpad }
enum class Button { A,B,X,Y,L1,R1,THUMBL,THUMBR,START,SELECT,MODE,DPAD_UP,DPAD_DOWN,DPAD_LEFT,DPAD_RIGHT }
enum class Direction { UP,DOWN,LEFT,RIGHT }
data class RangeInfo(val axis:Int,val source:Sources,val min:Float,val max:Float,val flat:Float=0f,val fuzz:Float=0f,val resolution:Float=0f){ val valid get()=min.isFinite()&&max.isFinite()&&min<max; val span get()=max-min }
data class DeviceInfo(val deviceId:Int,val descriptor:String?,val vendorId:Int,val productId:Int,val name:String,val controllerNumber:Int,val virtual:Boolean,val external:Boolean?,val sources:Sources,val keys:Set<Button>,val ranges:List<RangeInfo>)

object CandidateClassifier {
    private val axes=setOf(Axes.X,Axes.Y,Axes.Z,Axes.RZ,Axes.RX,Axes.RY,Axes.HAT_X,Axes.HAT_Y)
    fun isCandidate(d:DeviceInfo)=d.deviceId!=0&&!d.virtual&&(d.sources.gamepad||d.sources.joystick)&&(d.keys.isNotEmpty()||d.ranges.any{it.axis in axes&&it.source.joystick})
    fun candidates(devices:Iterable<DeviceInfo>)=devices.filter(::isCandidate).sortedBy{it.deviceId}
}

enum class Resolution { STANDARD,AMBIGUOUS,INCONCLUSIVE }
enum class MappingIssue { LEFT_MISSING,LEFT_INVALID,LEFT_AMBIGUOUS,RIGHT_MISSING,RIGHT_INVALID,RIGHT_ALTERNATIVE_ONLY,RIGHT_MULTIPLE,D_PAD_MISSING,D_PAD_INVALID,D_PAD_AMBIGUOUS }
data class AxisPair(val x:Int,val y:Int,val xr:RangeInfo,val yr:RangeInfo)
data class PairResolution(val state:Resolution,val pair:AxisPair?=null,val guidedConfirmation:Boolean=false,val issues:Set<MappingIssue> = emptySet())
enum class DpadMode { KEYS,HAT,DUAL,NONE }
data class Mapping(val left:PairResolution,val right:PairResolution,val dpad:DpadMode,val hat:AxisPair?,val buttons:Set<Button>,val issues:Set<MappingIssue>){ val ambiguous get()=left.state==Resolution.AMBIGUOUS||right.state==Resolution.AMBIGUOUS||MappingIssue.D_PAD_AMBIGUOUS in issues; val complete get()=left.state==Resolution.STANDARD&&right.state==Resolution.STANDARD&&dpad!=DpadMode.NONE&&buttons.isNotEmpty() }

object MappingResolver {
    private val dpadKeys=setOf(Button.DPAD_UP,Button.DPAD_DOWN,Button.DPAD_LEFT,Button.DPAD_RIGHT)
    fun resolve(d:DeviceInfo):Mapping{
        val left=pair(d,Axes.X,Axes.Y,MappingIssue.LEFT_MISSING,MappingIssue.LEFT_INVALID,MappingIssue.LEFT_AMBIGUOUS)
        val zr=pair(d,Axes.Z,Axes.RZ,MappingIssue.RIGHT_MISSING,MappingIssue.RIGHT_INVALID,MappingIssue.RIGHT_MULTIPLE)
        val rxry=optionalPair(d,Axes.RX,Axes.RY)
        val right=when{
            zr.state==Resolution.STANDARD&&rxry!=null->PairResolution(Resolution.AMBIGUOUS,issues=setOf(MappingIssue.RIGHT_MULTIPLE))
            zr.state==Resolution.STANDARD->zr.copy(guidedConfirmation=true)
            rxry!=null->PairResolution(Resolution.INCONCLUSIVE,guidedConfirmation=true,issues=zr.issues+MappingIssue.RIGHT_ALTERNATIVE_ONLY)
            else->zr
        }
        val hat=pair(d,Axes.HAT_X,Axes.HAT_Y,MappingIssue.D_PAD_MISSING,MappingIssue.D_PAD_INVALID,MappingIssue.D_PAD_AMBIGUOUS)
        val hasKeys=d.keys.any{it in dpadKeys}; val hasHat=hat.state==Resolution.STANDARD
        val mode=when{hasKeys&&hasHat->DpadMode.DUAL;hasKeys->DpadMode.KEYS;hasHat->DpadMode.HAT;else->DpadMode.NONE}
        val issues=buildSet{addAll(left.issues);addAll(right.issues);if(!hasKeys&&!hasHat)addAll(hat.issues);else if(hat.state==Resolution.AMBIGUOUS)addAll(hat.issues)}
        return Mapping(left,right,mode,hat.pair,d.keys-dpadKeys,issues)
    }
    private fun pair(d:DeviceInfo,x:Int,y:Int,missing:MappingIssue,invalid:MappingIssue,ambiguous:MappingIssue):PairResolution{
        val xs=d.ranges.filter{it.axis==x&&it.source.joystick}; val ys=d.ranges.filter{it.axis==y&&it.source.joystick}
        if(xs.size>1||ys.size>1)return PairResolution(Resolution.AMBIGUOUS,issues=setOf(ambiguous))
        val xr=xs.singleOrNull(); val yr=ys.singleOrNull(); if(xr==null||yr==null)return PairResolution(Resolution.INCONCLUSIVE,issues=setOf(missing))
        if(!xr.valid||!yr.valid)return PairResolution(Resolution.INCONCLUSIVE,issues=setOf(invalid))
        return PairResolution(Resolution.STANDARD,AxisPair(x,y,xr,yr))
    }
    private fun optionalPair(d:DeviceInfo,x:Int,y:Int):AxisPair?{
        val xs=d.ranges.filter{it.axis==x&&it.source.joystick};val ys=d.ranges.filter{it.axis==y&&it.source.joystick}
        if(xs.size!=1||ys.size!=1||!xs[0].valid||!ys[0].valid)return null
        return AxisPair(x,y,xs[0],ys[0])
    }
}

object Normalizer {
    fun normalize(raw:Float,range:RangeInfo):Float?{
        if(!raw.isFinite()||!range.valid||!range.source.joystick)return null
        val c=(range.min+range.max)/2f
        return if(raw>=c)(raw-c)/(range.max-c) else (raw-c)/(c-range.min)
    }
}

data class AxisSample(val timeMs:Long,val raw:Float,val normalized:Float?)
data class AxisMetrics(val count:Int,val durationMs:Long,val mean:Double,val median:Double,val mad:Double,val min:Float,val max:Float,val maxDeviation:Double,val coverage:Double?)
object Metrics {
    fun axis(samples:List<AxisSample>,range:RangeInfo?):AxisMetrics?{
        if(samples.isEmpty())return null
        val values=samples.map{it.raw.toDouble()}.sorted(); val median=median(values);val mean=values.average();val deviations=values.map{abs(it-median)}.sorted()
        val min=values.first().toFloat();val max=values.last().toFloat();val coverage=range?.takeIf{it.valid}?.let{((max-min)/it.span).toDouble()}
        return AxisMetrics(values.size,(samples.maxOf{it.timeMs}-samples.minOf{it.timeMs}).coerceAtLeast(0),mean,median,median(deviations),min,max,values.maxOf{abs(it-median)},coverage)
    }
    private fun median(v:List<Double>):Double=if(v.size%2==1)v[v.size/2] else (v[v.size/2-1]+v[v.size/2])/2.0
}

enum class KeyAction { DOWN,UP }
data class KeySample(val deviceId:Int,val source:Sources,val timeMs:Long,val keyCode:Int,val button:Button?,val action:KeyAction,val repeatCount:Int=0,val canceled:Boolean=false)
data class MotionFrame(val deviceId:Int,val source:Sources,val timeMs:Long,val axes:Map<Int,Float>)
data class Trajectory(val timeMs:Long,val rawX:Float,val rawY:Float,val normalizedX:Float?,val normalizedY:Float?)
data class ButtonMetrics(val presses:Int,val releases:Int,val pressed:Boolean)
data class StickMetrics(val x:AxisMetrics?,val y:AxisMetrics?,val trajectory:List<Trajectory>)
data class DpadSample(val timeMs:Long,val directions:Set<Direction>)

enum class SessionState { VALID,AMBIGUOUS,INCOMPLETE,INCONCLUSIVE,INVALIDATED }
enum class SessionIssue { NOT_PHYSICAL_CANDIDATE,DEVICE_REMOVED,DEVICE_CHANGED,SESSION_INTERRUPTED,INCOMPATIBLE_SOURCE,CANCELED_SEQUENCE,UNMATCHED_RELEASE,DPAD_CONTRADICTION,MAPPING_AMBIGUOUS,MAPPING_INCOMPLETE }
enum class Disposition { CONSUMED,OTHER_DEVICE,UNSUPPORTED,INCOMPATIBLE_SOURCE,INVALIDATED }
data class CaptureResult(val disposition:Disposition,val changed:Boolean=false){ val consumeInTestMode get()=disposition==Disposition.CONSUMED||disposition==Disposition.INCOMPATIBLE_SOURCE }

class ControlsSession(val device:DeviceInfo){
    val mapping=MappingResolver.resolve(device)
    private val issueSet=linkedSetOf<SessionIssue>(); val issues:Set<SessionIssue> get()=issueSet.toSet()
    var state:SessionState=initialState();private set
    val rawKeys=mutableListOf<KeySample>();val rawMotion=mutableListOf<MotionFrame>()
    private val press=mutableMapOf<Button,Int>();private val release=mutableMapOf<Button,Int>();private val pressed=mutableSetOf<Button>()
    private val leftX=mutableListOf<AxisSample>();private val leftY=mutableListOf<AxisSample>();private val rightX=mutableListOf<AxisSample>();private val rightY=mutableListOf<AxisSample>();private val leftPath=mutableListOf<Trajectory>();private val rightPath=mutableListOf<Trajectory>()
    private val keyDpad=linkedSetOf<Direction>();private val hatDpad=linkedSetOf<Direction>();private var sawKeys=false;private var sawHat=false;val dpadPath=mutableListOf<DpadSample>()
    fun acceptKey(s:KeySample):CaptureResult{
        if(state==SessionState.INVALIDATED)return CaptureResult(Disposition.INVALIDATED)
        if(s.deviceId!=device.deviceId)return CaptureResult(Disposition.OTHER_DEVICE)
        if(!s.source.control){degrade(SessionState.INCONCLUSIVE,SessionIssue.INCOMPATIBLE_SOURCE);return CaptureResult(Disposition.INCOMPATIBLE_SOURCE,true)}
        if(s.button==null)return CaptureResult(Disposition.UNSUPPORTED)
        rawKeys+=s
        if(s.canceled){degrade(SessionState.INCOMPLETE,SessionIssue.CANCELED_SEQUENCE);return CaptureResult(Disposition.CONSUMED,true)}
        if(s.button in setOf(Button.DPAD_UP,Button.DPAD_DOWN,Button.DPAD_LEFT,Button.DPAD_RIGHT)){sawKeys=true;updateKeyDpad(s)}else when(s.action){KeyAction.DOWN->if(s.repeatCount==0){press[s.button]=(press[s.button]?:0)+1;pressed+=s.button};KeyAction.UP->{if(s.button !in pressed)degrade(SessionState.INCOMPLETE,SessionIssue.UNMATCHED_RELEASE);release[s.button]=(release[s.button]?:0)+1;pressed-=s.button}}
        return CaptureResult(Disposition.CONSUMED,true)
    }
    fun acceptMotion(f:MotionFrame):CaptureResult{
        if(state==SessionState.INVALIDATED)return CaptureResult(Disposition.INVALIDATED)
        if(f.deviceId!=device.deviceId)return CaptureResult(Disposition.OTHER_DEVICE)
        if(!f.source.joystick){degrade(SessionState.INCONCLUSIVE,SessionIssue.INCOMPATIBLE_SOURCE);return CaptureResult(Disposition.INCOMPATIBLE_SOURCE,true)}
        rawMotion+=f;capturePair(f,mapping.left.pair,leftX,leftY,leftPath);capturePair(f,mapping.right.pair,rightX,rightY,rightPath);mapping.hat?.let{p->val x=f.axes[p.x];val y=f.axes[p.y];if(x!=null&&y!=null){sawHat=true;hatDpad.clear();if(x<0)hatDpad+=Direction.LEFT else if(x>0)hatDpad+=Direction.RIGHT;if(y<0)hatDpad+=Direction.UP else if(y>0)hatDpad+=Direction.DOWN;updateDpad(f.timeMs)}}
        return CaptureResult(Disposition.CONSUMED,true)
    }
    fun onRemoved(id:Int){if(id==device.deviceId)invalidate(SessionIssue.DEVICE_REMOVED)};fun onChanged(id:Int){if(id==device.deviceId)invalidate(SessionIssue.DEVICE_CHANGED)};fun interrupt(){invalidate(SessionIssue.SESSION_INTERRUPTED)}
    fun buttonMetrics(b:Button)=ButtonMetrics(press[b]?:0,release[b]?:0,b in pressed)
    fun leftMetrics()=StickMetrics(Metrics.axis(leftX,mapping.left.pair?.xr),Metrics.axis(leftY,mapping.left.pair?.yr),leftPath.toList())
    fun rightMetrics()=StickMetrics(Metrics.axis(rightX,mapping.right.pair?.xr),Metrics.axis(rightY,mapping.right.pair?.yr),rightPath.toList())
    private fun initialState():SessionState=when{!CandidateClassifier.isCandidate(device)->{issueSet+=SessionIssue.NOT_PHYSICAL_CANDIDATE;SessionState.INCONCLUSIVE};mapping.ambiguous->{issueSet+=SessionIssue.MAPPING_AMBIGUOUS;SessionState.AMBIGUOUS};!mapping.complete->{issueSet+=SessionIssue.MAPPING_INCOMPLETE;SessionState.INCONCLUSIVE};else->SessionState.VALID}
    private fun capturePair(f:MotionFrame,p:AxisPair?,xs:MutableList<AxisSample>,ys:MutableList<AxisSample>,path:MutableList<Trajectory>){p?:return;val x=f.axes[p.x]?:return;val y=f.axes[p.y]?:return;val nx=Normalizer.normalize(x,p.xr);val ny=Normalizer.normalize(y,p.yr);xs+=AxisSample(f.timeMs,x,nx);ys+=AxisSample(f.timeMs,y,ny);path+=Trajectory(f.timeMs,x,y,nx,ny)}
    private fun updateKeyDpad(s:KeySample){val d=when(s.button){Button.DPAD_UP->Direction.UP;Button.DPAD_DOWN->Direction.DOWN;Button.DPAD_LEFT->Direction.LEFT;Button.DPAD_RIGHT->Direction.RIGHT;else->return};when(s.action){KeyAction.DOWN->if(s.repeatCount==0)keyDpad+=d;KeyAction.UP->keyDpad-=d};updateDpad(s.timeMs)}
    private fun updateDpad(t:Long){if(sawKeys&&sawHat&&keyDpad.isNotEmpty()&&hatDpad.isNotEmpty()&&keyDpad!=hatDpad)degrade(SessionState.AMBIGUOUS,SessionIssue.DPAD_CONTRADICTION);val logical=when{keyDpad.isNotEmpty()&&keyDpad==hatDpad->keyDpad.toSet();keyDpad.isNotEmpty()->keyDpad.toSet();else->hatDpad.toSet()};if(dpadPath.lastOrNull()?.directions!=logical)dpadPath+=DpadSample(t,logical)}
    private fun degrade(target:SessionState,issue:SessionIssue){issueSet+=issue;if(state!=SessionState.INVALIDATED&&severity(target)>severity(state))state=target}
    private fun severity(value:SessionState)=when(value){SessionState.VALID->0;SessionState.INCOMPLETE->1;SessionState.INCONCLUSIVE->2;SessionState.AMBIGUOUS->3;SessionState.INVALIDATED->4}
    private fun invalidate(issue:SessionIssue){issueSet+=issue;state=SessionState.INVALIDATED}
}
