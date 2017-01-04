package jvmdbbroker.core

import java.util.concurrent.atomic.{AtomicBoolean,AtomicInteger}
import java.io._
import scala.collection.mutable.{HashMap,ArrayBuffer,Buffer}
import scala.io.Source
import scala.xml._
import java.util.concurrent._
import java.util.concurrent.locks.ReentrantLock
import java.text.SimpleDateFormat
import scala.reflect.runtime.universe

// todo 字符串嵌入${...}
// 参数也可以是表达式
// mock,setup的顺序可以在中间，而不是总最前:拆成2个testcase, 后面的可以引用前面的testcase
object ValueParser {

    var showEscape = false

    var pluginObjectName = "jvmdbbroker.flow.FlowHelper"
    val runtimeMirror = universe.runtimeMirror(getClass.getClassLoader)
    val module = runtimeMirror.staticModule(pluginObjectName)
    val pluginobj = runtimeMirror.reflectModule(module)

    // tp: v=value a=array value f=function
    class Field(val key:String,val tp:String, val params:Array[String] = null) {
        override def toString():String={
            if( params == null )
                "key=%s,tp=%s,params=null".format(key,tp)
            else
                "key=%s,tp=%s,params=%s".format(key,tp,params.mkString("#"))
        }
    }

    val r1 = """(\$\{[^}]+\})""".r
    val r2 = """(\$[^ ]+)""".r

    def parse(s:String,localCtx:HashMapStringAny,glbCtx:HashMapStringAny,returnNull:Boolean):Any = {
        if( s.indexOf("$") >= 0 ) {

            if( showEscape )
                println("***"+s)            
            var ns = escape3(escape4(s))
            if( showEscape )
                println("###"+ns)            
            ns = r1.replaceAllIn(ns,(m)=>parseInternalPart1(m.group(1),localCtx,glbCtx).replace("$","\\$"))
            if( showEscape )
                println("@@@"+ns)            
            ns = r2.replaceAllIn(ns,(m)=>parseInternalPart2(m.group(1),localCtx,glbCtx).replace("$","\\$"))
            if( showEscape )
                println("%%%"+ns)            
            return ns
        }

        val v = parseInternal(s,localCtx,glbCtx)
        if( v == null && returnNull ) return null
        if( v == null && !returnNull ) return s
        v
    }

    def parseInternalPart1(s:String,localCtx:HashMapStringAny,glbCtx:HashMapStringAny):String = {
        var ns = unescape(s)
        ns = "$"+s.substring(2,ns.length-1)
        val v = parseInternal(ns,localCtx,glbCtx)
        if( v == null ) return ""
        v.toString
    }

    def parseInternalPart2(s:String,localCtx:HashMapStringAny,glbCtx:HashMapStringAny):String = {
        var ns = unescape(s)
        val v = parseInternal(ns,localCtx,glbCtx)
        if( v == null ) return ""
        v.toString
    }

    def parseInternal(s:String,localCtx:HashMapStringAny,glbCtx:HashMapStringAny):Any = {
        val fields = parseFields(s)
        if( showEscape )
            println("fields="+fields)            
        if( fields == null ) return null

        if( localCtx != null ) {
            val v = parseInternal2(fields,localCtx,localCtx,glbCtx)
            if( v != null ) return v
        }
        parseInternal2(fields,glbCtx,localCtx,glbCtx)
    }

    def parseInternal2(fields:ArrayBuffer[Field],curCtx:HashMapStringAny,localCtx:HashMapStringAny,glbCtx:HashMapStringAny):Any = {
        var c = curCtx
        var obj:Any = null

        for( i <- 0 until fields.size ) {
            val f = fields(i)
            var v:Any = null
            f.tp match {
                case "v" | "a" =>
                    if( c == null ) return null // must have a context
                    v = getFieldValue(f,c,localCtx,glbCtx)
                case "f" if c != null =>
                    v = getFieldValue(f,c,localCtx,glbCtx)
                case "f" if obj != null =>
                    v = callObjectFunction(obj,f.key,convertParams(f.params,localCtx,glbCtx))
                case _ => 
                    return null
            }
            if( v == null ) return null
            if( i == fields.size - 1 ) return v
            if( v.isInstanceOf[HashMapStringAny] ) {
                c = v.asInstanceOf[HashMapStringAny]
                obj = null
            } else {
                c = null
                obj = v
            }
        }
        null
    }

    def getFieldValue(f:Field,curCtx:HashMapStringAny,localCtx:HashMapStringAny,glbCtx:HashMapStringAny):Any = {
        f.tp match {
            case "v" =>
                curCtx.getOrElse(f.key,null)
            case "a" =>
                getArrayValue(f.key,f.params(0).toInt,curCtx)
            case "f" =>
                if( f.key.startsWith("$") )
                    return callFunction(f.key,convertParams(f.params,localCtx,glbCtx))
                else
                    return callObjectFunction(curCtx,f.key,convertParams(f.params,localCtx,glbCtx))
            case _ =>
                null
        }
    }

    def convertParams(params:Array[String],localCtx:HashMapStringAny,glbCtx:HashMapStringAny):Array[String] = {
        val news = new Array[String](params.length)
        for( i <- 0 until params.size ) {
            news(i) = parse(params(i),localCtx,glbCtx,false).toString.trim
        }
        news
    }

    def getArrayValue(key:String,idx:Int,context:HashMapStringAny):Any = {
        val v = context.getOrElse(key,null)
        if( v == null ) return null
        v match {
            case a:ArrayBufferString =>
                if( idx < 0 || idx >= a.size ) return null
                return a(idx)
            case a:ArrayBufferInt =>
                if( idx < 0 || idx >= a.size ) return null
                return a(idx)
            case a:ArrayBufferMap =>
                if( idx < 0 || idx >= a.size ) return null
                return a(idx)
            case a:ArrayBufferAny =>
                if( idx < 0 || idx >= a.size ) return null
                return a(idx)
            case _ =>
                return null
        }
    }

    def callFunction(fun:String,params:Array[String]):Any = {
        fun match {
            case "$now" =>
                return now()
            case "$uuid" =>
                return uuid()
            case _ =>
                val methodName = fun.substring(1)
                params.size match {
                    case 0 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName)
                          method.invoke(pluginobj.instance)
                        } catch {
                          case e: Throwable => 
                            null
                        }
                    case 1 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[String])
                          method.invoke(pluginobj.instance,params(0))
                        } catch {
                          case e: Throwable => 
                            null
                        }
                    case 2 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[String],classOf[String])
                          method.invoke(pluginobj.instance,params(0),params(1))
                        } catch {
                          case e: Throwable => 
                        }
                    case 3 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[String],classOf[String],classOf[String])
                          method.invoke(pluginobj.instance,params(0),params(1),params(2))
                        } catch {
                          case e: Throwable => 
                              null
                        }
                    case _ =>
                        null
                }
        }
    }

    def callStringFunction(s:String,fun:String,params:Array[String]):Any = {
        fun match {
            case "size" | "length" => 
                s.length
            case "toString" => 
                s.toString
            case "matches" => 
                if( params.size == 0 ) return null
                s.matches(params(0)).toString
            case "contains" => 
                if( params.size == 0 ) return null
                (s.indexOf(params(0)) >= 0).toString
            case "indexOf" => 
                if( params.size == 0 ) return null
                s.indexOf(params(0)).toString
            case "left" => 
                if( params.size == 0 ) return null
                s.substring(0,params(0).toInt).toString
            case "right" => 
                if( params.size == 0 ) return null
                val len = s.length
                if( params(0).toInt > len ) return null
                s.substring(len-params(0).toInt).toString
            case _ => 
                val methodName = fun
                params.size match {
                    case 0 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[String])
                          method.invoke(pluginobj.instance,s)
                        } catch {
                          case e: Throwable => 
                            null
                        }
                    case 1 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[String],classOf[String])
                          method.invoke(pluginobj.instance,s,params(0))
                        } catch {
                          case e: Throwable => 
                            null
                        }
                    case 2 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[String],classOf[String],classOf[String])
                          method.invoke(pluginobj.instance,s,params(0),params(1))
                        } catch {
                          case e: Throwable => 
                        }
                    case 3 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[String],classOf[String],classOf[String],classOf[String])
                          method.invoke(pluginobj.instance,s,params(0),params(1),params(2))
                        } catch {
                          case e: Throwable => 
                              null
                        }
                    case _ =>
                        null
                }
        }
    }

    def callMapFunction(m:HashMapStringAny,fun:String,params:Array[String]):Any = {
        fun match {
            case "size" => m.size
            case "contains" if params.size == 0 => null
            case "contains" if params.size > 0 => m.contains(params(0)).toString
            case "toString" => 
                m.toString()
            case "toJson" => 
                JsonCodec.mkString(m)
            case _ => 
                val methodName = fun
                params.size match {
                    case 0 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[HashMapStringAny])
                          method.invoke(pluginobj.instance,m)
                        } catch {
                          case e: Throwable => 
                            null
                        }
                    case 1 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[HashMapStringAny],classOf[String])
                          method.invoke(pluginobj.instance,m,params(0))
                        } catch {
                          case e: Throwable => 
                            null
                        }
                    case 2 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[HashMapStringAny],classOf[String],classOf[String])
                          method.invoke(pluginobj.instance,m,params(0),params(1))
                        } catch {
                          case e: Throwable => 
                        }
                    case 3 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[HashMapStringAny],classOf[String],classOf[String],classOf[String])
                          method.invoke(pluginobj.instance,m,params(0),params(1),params(2))
                        } catch {
                          case e: Throwable => 
                              null
                        }
                    case _ =>
                        null
                }
        }
    }
    def callArrayFunction(a:ArrayBufferAny,fun:String,params:Array[String]):Any = {
        fun match {
            case "size" => a.size
            case "contains" if params.size == 0 => null
            case "contains" if params.size > 0 => a.contains(params(0)).toString
            case "toString" => 
                a.toString()
            case "toJson" => 
                JsonCodec.mkString(a)
            case _ => 
                val methodName = fun
                params.size match {
                    case 0 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[ArrayBufferAny])
                          method.invoke(pluginobj.instance,a)
                        } catch {
                          case e: Throwable => 
                            null
                        }
                    case 1 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[ArrayBufferAny],classOf[String])
                          method.invoke(pluginobj.instance,a,params(0))
                        } catch {
                          case e: Throwable => 
                            null
                        }
                    case 2 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[ArrayBufferAny],classOf[String],classOf[String])
                          method.invoke(pluginobj.instance,a,params(0),params(1))
                        } catch {
                          case e: Throwable => 
                        }
                    case 3 =>
                        try {
                          val method = pluginobj.instance.getClass.getMethod(methodName,classOf[ArrayBufferAny],classOf[String],classOf[String],classOf[String])
                          method.invoke(pluginobj.instance,a,params(0),params(1),params(2))
                        } catch {
                          case e: Throwable => 
                              null
                        }
                    case _ =>
                        null
                }
        }
    }

    def callObjectFunction(obj:Any,fun:String,params:Array[String]):Any = {
        obj match {
            case m:HashMapStringAny =>
                callMapFunction(m,fun,params)
            case a:ArrayBufferString =>
                val aa = ArrayBufferAny()
                a.foreach( aa += _ )
                callArrayFunction(aa,fun,params)
            case a:ArrayBufferInt =>
                val aa = ArrayBufferAny()
                a.foreach( aa += _.toString )
                callArrayFunction(aa,fun,params)
            case a:ArrayBufferMap =>
                val aa = ArrayBufferAny()
                a.foreach( aa += _ )
                callArrayFunction(aa,fun,params)
            case a:ArrayBufferAny =>
                callArrayFunction(a,fun,params)
            case _ =>
                callStringFunction(obj.toString,fun,params)
        }
    }

    val f0 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    def now():String={
        f0.format(new java.util.Date())
    }

    def uuid(): String = {
        java.util.UUID.randomUUID().toString().replaceAll("-", "")
    }
    
    val sep1 = 10001.toChar // . in "" 
    val sep2 = 10002.toChar // , in ""
    val sep3 = 10003.toChar // [ in ""
    val sep4 = 10004.toChar // ] in ""
    val sep5 = 10005.toChar // ( in ""
    val sep6 = 10006.toChar // ) in ""
    val sep7 = 10007.toChar // $ in ""
    val sep8 = 10008.toChar // { in ""
    val sep9 = 10009.toChar // } in ""
    val sep10 = 10010.toChar // blank in ""

    def escape(s:String):String = {
        escape3(escape2(escape1(s)))
    }

    def escape1(s:String):String = {
        var afterSlash = false
        var ts = ""
        for(c <- s) {
            if( afterSlash ) {
                c match {
                    case '.' =>
                        ts = ts + sep1
                    case ',' =>
                        ts = ts + sep2
                    case '[' =>
                        ts = ts + sep3
                    case ']' =>
                        ts = ts + sep4
                    case '(' =>
                        ts = ts + sep5
                    case ')' =>
                        ts = ts + sep6
                    case '$' =>
                        ts = ts + sep7
                    case _ =>
                        ts = ts + '\\'
                        ts = ts + c
                }
                afterSlash = false
            } else if( c == '\\')  { 
                afterSlash = true
            } else {
                ts = ts + c
            }
        }
        if( afterSlash )
            ts = ts + '\\'
        ts
    }

    def escape2(s:String):String = {
        var inQuota = false
        var ts = ""
        for(c <- s) {
            if( c == '\"')  { 
                inQuota = !inQuota
                ts = ts + "\""
            } else if( inQuota ) {
                c match {
                    case '.' =>
                        ts = ts + sep1
                    case ',' =>
                        ts = ts + sep2
                    case '[' =>
                        ts = ts + sep3
                    case ']' =>
                        ts = ts + sep4
                    case '(' =>
                        ts = ts + sep5
                    case ')' =>
                        ts = ts + sep6
                    case '$' =>
                        ts = ts + sep7
                    case _ =>
                        ts = ts + c
                }
            } else {
                ts = ts + c
            }
        }
        ts
    }
    def escape3(s:String):String = {
        var brackets = 0
        var ts = ""
        for(c <- s) {
            /*if( c == '.' && brackets > 0 ) {
                ts = ts + sep1
            } else if( c == '(' )  { 
                brackets += 1
                ts = ts + "("
            } else if( c == ')' ) { 
                brackets -= 1
                if( brackets < 0 ) brackets = 0
                ts = ts + ")"
            } else {
                ts = ts + c
            }*/

            if( brackets > 0 ) {
                c match {
                    case '.' =>
                        ts = ts + sep1
                    case ' ' =>
                        ts = ts + sep10
                    case '$' =>
                        ts = ts + sep7
                    case '(' =>
                        brackets += 1
                        ts = ts + sep5
                    case ')' =>
                        brackets -= 1
                        if( brackets < 0 ) brackets = 0
                        if( brackets == 0 )
                            ts = ts + ")" 
                        else 
                            ts = ts + sep6 
                    case _ =>
                        ts = ts + c
                }
            } else if( c == '(' )  { 
                brackets += 1
                ts = ts + "("
            } else {
                ts = ts + c
            }

        }
        ts
    }

    def escape4(s:String):String = {
        var brackets = 0
        var ts = ""
        var lastch = ' '
        for(c <- s) {
            if( brackets > 0 ) {
                c match {
                    case '$' =>
                        ts = ts + sep7
                    case '{' =>
                        brackets += 1
                        ts = ts + sep8
                    case '}' =>
                        brackets -= 1
                        if( brackets < 0 ) brackets = 0
                        if( brackets == 0 )
                            ts = ts + "}" 
                        else
                            ts = ts + sep9 
                    case _ =>
                        ts = ts + c
                }
            } else if( c == '{' && lastch == '$' )  { 
                brackets += 1
                ts = ts + "{"
            } else {
                ts = ts + c
            }
            lastch = c
        }
        ts
    }

    def unescape(s:String):String = {
        var ts = ""
        for(ch <- s) {
            ch match {
                case c if c == sep1 =>
                    ts += "."
                case c if c == sep2 =>
                    ts += ","
                case c if c == sep3 =>
                    ts += "["
                case c if c == sep4 =>
                    ts += "]"
                case c if c == sep5 =>
                    ts += "("
                case c if c == sep6 =>
                    ts += ")"
                case c if c == sep7 =>
                    ts += "$"
                case c if c == sep8 =>
                    ts += "{"
                case c if c == sep9 =>
                    ts += "}"
                case c if c == sep10 =>
                    ts += " "
                case c =>
                    ts += c
            }
        }
        ts
    }

    def unescapeAndRemoveQuota(s:String):String = {
        val t = unescape( s.trim() )
        //if( t.length >= 2 && t.startsWith("\"") &&  t.endsWith("\"") ) return t.substring(1,t.length-1)
        t
    }

    def parseFields(s0:String):ArrayBuffer[Field] = {
        val s1 = escape(s0)
        val ss = s1.split("\\.")
        val fields = new ArrayBuffer[Field]()
        for( s <- ss ) {
            val f = parseSingleField(s)
            if( f == null ) return null
            fields += f
        }
        if( fields.size == 0 ) return null 
        fields
    }
    def parseSingleField(s:String):Field = {
        val p01 = s.indexOf("(")
        val p02 = s.indexOf("[")
        (p01,p02) match {
            case (-1,-1) =>
                new Field(unescapeAndRemoveQuota(s),"v")
            case (p1,-1) =>
                parseFunctionField(s)
            case (-1,p1) =>
                parseArrayField(s)
            case (p1,p2) if p1 < p2 =>
                parseFunctionField(s)
            case (p1,p2) if p1 > p2 =>
                parseArrayField(s)
            case _ =>
                null
        }
    }

    def parseArrayField(s:String):Field = {
        val p1 = s.indexOf("[")
        if( p1 == 0 ) return null
        if( !s.trim.endsWith("]")) return null
        val key = unescapeAndRemoveQuota(s.substring(0,p1).trim)
        val p2 = s.lastIndexOf("]")
        val ps = s.substring(p1+1,p2).trim
        if( !isInt(ps) ) return null
        if( ps.toInt < 0 ) return null
        val f = new Field(key,"a",Array[String](ps))
        return f
    }
    def parseFunctionField(s:String):Field = {
        val p1 = s.indexOf("(")
        if( p1 == 0 ) return null
        if( !s.trim.endsWith(")")) return null
        val key = unescapeAndRemoveQuota(s.substring(0,p1).trim)
        val p2 = s.lastIndexOf(")")
        val ps = s.substring(p1+1,p2).trim
        var params = ps.split(",").map(_.trim)
        if( params.size == 1 && params(0) == "" ) params = Array[String]()
        for( i <- 0 until params.size ) params(i) = unescapeAndRemoveQuota(params(i))
        val f = new Field(key,"f",params)
        return f
    }

    def isInt(n:String):Boolean={
        try {
            Integer.parseInt(n)
            return true
        } catch {
            case e: Throwable =>
             return false
        }
    }
}

class MockActor extends Actor with Logging with SyncedActor {

    val retmap = new ConcurrentHashMap[String,Response]()

    override def receive(v:Any) :Unit = {
        v match {
            case req: Request =>

                val buff = Router.main.mocks.getOrElse(req.serviceId+":"+req.msgId,null)
                if( buff == null ) {
                    reply(req,-10242504)
                    return
                }

                genResponse(req,buff)

            case _ =>
                log.error("unknown msg")
        }
    }

    def checkMatch(req:Request,cfg:MockCfg):Boolean =  {
        for( (k,v) <- cfg.req ) {
            v match {
                case s:String if s == "NULL" => 
                    if( req.s(k) != null ) return false
                case s:String => 
                    if( req.ns(k) != s ) return false
                case i:Int => 
                    if( !req.body.contains(k) ) return false
                    if( req.i(k) != i ) return false
                case _ => 
            }
        }
        true
    }

    def getCfg(req:Request,cfgs:ArrayBuffer[MockCfg]):MockCfg = {
        for( cfg <- cfgs ) {
            if( cfg.req.size == 0 ) return cfg
            if( checkMatch(req,cfg) ) return cfg
        }
        null
    }

    def genResponse(req:Request,cfgs:ArrayBuffer[MockCfg]) {
        val cfg = getCfg(req,cfgs)
        if( cfg == null ) {
            reply(req,-10242404)
            return
        }
        val code = cfg.res.i("$code")
        val params = HashMapStringAny()
        params ++= cfg.res
        if( code != 0 ) {
            reply(req,code)
            return
        }
        reply(req,0,params)
    }

    def reply(req:Request, code:Int) :Unit ={
        reply(req,code,new HashMapStringAny())
    }

    def reply(req:Request, code:Int, params:HashMapStringAny):Unit = {
        val (newbody,ec) = Router.main.encodeResponse(req.serviceId,req.msgId,code,params)
        var errorCode = code
        if( errorCode == 0 && ec != 0 ) {
            errorCode = ec
        }

        val res = new Response (errorCode,newbody,req)
        put(res.requestId,res)
    }

    def get(requestId:String): Response = {
        retmap.remove(requestId)
    }

    def put(requestId:String,ret: Response) {
        retmap.put(requestId,ret)
    }

}

class TestCaseV2Define(val defines:LinkedHashMapStringAny) {
    var lineNo = 0
    def toString(m:LinkedHashMapStringAny):String = {
        val b = new StringBuilder()
        for( (k,v) <- m ) {
            b.append(" ").append(k).append("=").append(v)
        }
        b.toString
    }
    def toString(indent:String):String = {
        var s = indent + "define: %s".format(toString(defines))
        s
    }
}
class TestCaseV2Invoke(val tp:String, val service:String, val timeout:Int, val req:LinkedHashMapStringAny,val res:LinkedHashMapStringAny,val id:String = "") {
    var lineNo = 0
    def toString(m:LinkedHashMapStringAny):String = {
        val b = new StringBuilder()
        for( (k,v) <- m ) {
            b.append(" ").append(k).append("=").append(v)
        }
        b.toString
    }
    def toString(indent:String):String = {
        var s = indent + "%s:%s id:%s timeout:%d req:%s res:%s".format(tp,service,id,timeout,toString(req),toString(res))
        s = s.replace(" timeout:15000 "," ")
        s = s.replace(" id: "," ")
        s
    }
}

class TestCaseV2(val tp:String,val name:String,val mocks:ArrayBuffer[TestCaseV2Invoke],val setups:ArrayBuffer[TestCaseV2Invoke],val teardowns:ArrayBuffer[TestCaseV2Invoke],val asserts:ArrayBuffer[TestCaseV2Invoke],val defines:ArrayBuffer[TestCaseV2Define]) {

    var lineNo = 0
    var enabled = true
    override def toString():String = {

        val indent = "    "

        val buff = ArrayBufferString()

        if( name == "global")
            buff += "global:"
        else
            buff += "testcase:" + name +" enabled:"+enabled

        if( defines != null )
            defines.foreach(buff += _.toString(indent))
        if( mocks != null )
            mocks.foreach(buff += _.toString(indent))
        if( setups != null )
            setups.foreach(buff += _.toString(indent))
        if( teardowns != null )
            teardowns.foreach(buff += _.toString(indent))
        if( asserts != null )
            asserts.foreach(buff += _.toString(indent))

        buff.mkString("\n")
    }
}

object TestCaseRunner {

    val indent = "    "
    val codeTag = "$code"
    val savedMocks = HashMap[String,ArrayBuffer[MockCfg]]()
    var sequence = new AtomicInteger(1)
    val lock = new ReentrantLock(false)
    val replied = lock.newCondition()
    var testCaseCount = 0
    var ir: InvokeResult = _

    var runAll = false

    var total = 0
    var success = 0
    var failed = 0

    var lineNoCnt = 0

    object TestActor extends Actor {
        def receive(v:Any) {
            v match {
                case res : InvokeResult =>
                    lock.lock();
                    try {
                       ir = res 
                       replied.signal()
                    } finally {
                        lock.unlock();
                    }
                case _ =>
                    println("unknown msg")
            }
        }
    }

    def main(args:Array[String]) {

        var file = "."+File.separator+"testcase"+File.separator+"default.txt"
        var s = TestCaseRunnerV1.parseFile(args)
        if( s != "" ) file = s
        if(!isNewFormat(file)) {
            TestCaseRunnerV1.main(args)
            return
        }

        var dumpFlag = TestCaseRunnerV1.parseArg(args,"d")
        runAll = TestCaseRunnerV1.parseArg(args,"a") == "1"
        ValueParser.showEscape = TestCaseRunnerV1.parseArg(args,"e") ==  "1"

        val lines = Source.fromFile(file,"UTF-8").getLines.toBuffer.map( (s:String) => s.trim).map(appendLineNo).map(removeComment).map(_.replace("\t"," ")).map(_.trim).filter( _.trim != "")
        val mergedlines2 = mergeLines(lines).filter( !_.startsWith("#") )
        val mergedlines = ArrayBufferString()
        mergedlines2.foreach(mergedlines += _)
        val (global,testcases) = parseFile(mergedlines)
        if( testcases == null ) {
            return
        }

        Main.mockMode = true
        Main.main(Array[String]())
        Router.main.mockActor = new MockActor()

        println("-------------------------------------------")
        println("testcase file:  " + file)

        if( dumpFlag == "1" ) {
            println("-------------------------------------------")
            dump(global,testcases)
        }

        println("-------------------------------------------")
        try {
            runTest(global,testcases)
        } catch {
            case e:Throwable =>
                if( e.getMessage()== "stop")
                    println(">>> stop --- interrupted! ---")
                else
                    throw e
        }

        println("-------------------------------------------")
        println("testcase total:%d, success:%d, failed:%d".format(total,success,failed))
        println("-------------------------------------------")
        Main.close()
    }

    def runTest(global:TestCaseV2,testcases:ArrayBuffer[TestCaseV2]) {
        val context = new HashMapStringAny()

        if( global != null && global.defines != null ) {
            for( d <- global.defines ) {
                installDefines(d,context)
            }
        }
        if( context.ns("$pluginObjectName") != "" )
            ValueParser.pluginObjectName = context.ns("$pluginObjectName")

        Router.main.mocks.clear()
        if( global != null && global.mocks != null ) {
            for( m <- global.mocks ) {
                val (ok,msg) = installMock(m,context)
                if( !ok ) {
                    println(">>> LINE#"+m.lineNo+" "+m.toString(""))
                    println("<<< global mock install failed, stop test! service="+m.service+", reason="+msg)
                    return
                }
            }
        }

        saveGlobalMock()

        if( global != null && global.setups != null ) {
            for( i <- global.setups ) {
                val (ok,msg,req,res) = callServiceMustOk(i,context)
                if( !ok ) {
                    println(">>> LINE#"+i.lineNo+" "+i.toString(""))
                    println(">>> "+req)
                    println("<<< "+res)
                    println("<<< global setup failed, stop test! service="+i.service+", reason="+msg)
                    return
                }
            }
        }
        if( testcases != null ) {
            for( t <- testcases ) {
                doTest(t,context)
            }
        }
        if( global != null && global.teardowns != null ) {
            for( i <- global.teardowns ) {
                callServiceIgnoreResult(i,context)
            }
        }
    }

    def doTest(t:TestCaseV2,context:HashMapStringAny) {
        if( !t.enabled && !runAll ) return
        Router.main.mocks.clear()
        Router.main.mocks ++= savedMocks

        if( t.defines != null ) {
            for( d <- t.defines ) {
                installDefines(d,context)
            }
        }

        if( t.mocks != null ) {
            for( m <- t.mocks ) {
                val (ok,msg) = installMock(m,context)
                if( !ok ) {
                    failed += 1
                    total += 1
                    println(">>> LINE#"+m.lineNo+" "+m.toString(""))
                    println("<<< testcase mock failed, testcase=%s, service=%s, reason=%s".format(t.name,m.service,msg))
                    return
                }
            }
        }
        if( t.setups != null ) {
            for( i <- t.setups ) {
                val (ok,msg,req,res) = callServiceMustOk(i,context)
                if( !ok ) {
                    failed += 1
                    total += 1
                    println(">>> LINE#"+i.lineNo+" "+i.toString(""))
                    println(">>> "+req)
                    println("<<< "+res)
                    println("<<< testcase setup failed, testcase=%s, service=%s, reason=%s".format(t.name,i.service,msg))
                    return
                }
            }
        }

        if( t.asserts != null ) {
            for( i <- t.asserts ) {
                val (ok,msg,req,res) = callServiceWithAssert(i,context)
                if( !ok ) {
                    failed += 1
                    total += 1
                    println(">>> LINE#"+i.lineNo+" "+i.toString(""))
                    println(">>> "+req)
                    println("<<< "+res)
                    println("<<< assert failed, testcase=%s, service=%s, reason=%s".format(t.name,i.service,msg))
                    println("-------------------------------------------")
                } else {
                    success += 1
                    total += 1
                }
            }
        }

        if( t.teardowns != null ) {
            for( i <- t.teardowns ) {
                callServiceIgnoreResult(i,context)
            }
        }
    }

    def callServiceIgnoreResult(i:TestCaseV2Invoke,context:HashMapStringAny) {
        callService(i,context)
    }
    def callServiceMustOk(i:TestCaseV2Invoke,context:HashMapStringAny):Tuple4[Boolean,String,String,String] = {
        callServiceWithAssert(i,context)
    }
    def callServiceWithAssert(i:TestCaseV2Invoke,context:HashMapStringAny):Tuple4[Boolean,String,String,String] = {
        val (req,ret) = callService(i,context)
        val resMap = HashMapStringAny()
        for( (k,v) <- i.res ) {
            resMap.put(k,parseRightValue(v,context))
        }
        for( (k,v) <- resMap ) {
            if( v == "NULL" && ret.contains(k) )
                return (false,"["+k+"] not match, required:null, actual:not null",req.toString,ret.toString)

            v match {
                case s:String => 
                    val rets = parseLeftValue(k,ret,context)
                    if( rets != s ) return (false,"["+k+"] not match, required:"+s+", actual:"+rets,req.toString,ret.toString)

                case i:Int => 
                    val s = i.toString
                    val rets = parseLeftValue(k,ret,context)
                    if( rets != s ) return (false,"["+k+"] not match, required:"+s+", actual:"+rets,req.toString,ret.toString)

                case m:HashMapStringAny =>
                    val s = JsonCodec.mkString(m)
                    val rets = parseLeftValue(k,ret,context)
                    if( rets != s ) return (false,"["+k+"] not match, required:"+s+", actual:"+rets,req.toString,ret.toString)
                    
                case a:ArrayBufferAny =>
                    val s = JsonCodec.mkString(a)
                    val rets = parseLeftValue(k,ret,context)
                    if( rets != s ) return (false,"["+k+"] not match, required:"+s+", actual:"+rets,req.toString,ret.toString)

                case _ => 
            }
        }
        (true,"success",req.toString,ret.toString)
    }


    def generateSequence():Int = {
        sequence.getAndIncrement()
    }

    def saveInvokeToContext(id:String, tp:Tuple2[HashMapStringAny,HashMapStringAny], context:HashMapStringAny ) {
        if( id == "" ) return
        val map = HashMapStringAny("req"->tp._1,"res"->tp._2)
        context.put(id,map)
    }

    def callService(i:TestCaseV2Invoke,context:HashMapStringAny):Tuple2[HashMapStringAny,HashMapStringAny] = {
            val params = HashMapStringAny()
            for( (k,v) <- i.req ) {
                params.put(k,parseRightValue(v,context))
            }
            if( i.service.toLowerCase == "sleep" ) {
                val s = params.i("s")
                val ms = params.i("ms")
                val t = if( s == 0 ) ms else s*1000
                val m = params.ns("m")
                if( m != "") println(">>> sleeping --- " + m) 
                Thread.sleep(t)
                val tp = (params,HashMapStringAny("$code"->0))
                saveInvokeToContext(i.id,tp,context)
                return tp
            }
            if( i.service.toLowerCase == "echo" ) {
                val m = params.ns("m")
                if( m != "") println(">>> echo --- " + m) 
                val tp = (params,HashMapStringAny("$code"->0,"m"->m))
                saveInvokeToContext(i.id,tp,context)
                return tp
            }
            if( i.service.toLowerCase == "stop" ) {
                throw new Exception("stop")
            }
            val (serviceId,msgId) = Flow.router.serviceNameToId(i.service)
            if( serviceId == 0 || msgId == 0 ) {
                val tp = (params,HashMapStringAny("$code"-> (-10242405)))
                saveInvokeToContext(i.id,tp,context)
                return tp
            }

            val (newbody,ec) = Flow.router.encodeRequest(serviceId, msgId, params)
            if( ec != 0 ) {
                val tp = (params,HashMapStringAny("$code"-> (-10242400)))
                saveInvokeToContext(i.id,tp,context)
                return tp
            }

            val requestId = "TEST"+RequestIdGenerator.nextId()
            val map = HashMapStringAny()
            lock.lock();
            try {
                val req = new Request (
                    requestId,
                    "test:0",
                    generateSequence(),
                    1,
                    serviceId,
                    msgId,
                    new HashMapStringAny(),
                    newbody,
                    TestActor
                )

                ir = null
                ir = Router.main.send(req)

                if( ir == null ) {
                    replied.await( i.timeout, TimeUnit.MILLISECONDS )
                } 
                
                if( ir != null ) {
                    map ++= ir.res
                    map.put("$code",ir.code)
                } else {
                    map.put("$code",-10242504)
                }

                newbody.put("$requestId",requestId)

            } finally {
                lock.unlock();
            }
            val tp = (newbody,map)
            saveInvokeToContext(i.id,tp,context)
            return tp
    }

    def saveGlobalMock() {
        for( (k,buff) <- Router.main.mocks ) {
            val newbuff = ArrayBuffer[MockCfg]()
            newbuff ++= buff
            savedMocks.put(k,newbuff)
        }
    }

    // 目前仅支持最简单的常量，不允许变量，不支持作用域, 相同名字的后定义的会覆盖前面的定义
    def installDefines(d:TestCaseV2Define,context:HashMapStringAny) {
        for( (k,v) <- d.defines ) {
            context.put(k,parseRightValue(v,context))
        }
    }

    def installMock(m:TestCaseV2Invoke,context:HashMapStringAny):Tuple2[Boolean,String] = {
        val service = m.service.toLowerCase
        val (serviceId,msgId) = Flow.router.serviceNameToId(service)
        if( serviceId == 0 || msgId == 0 ) {
            return (false,"mock failed, service not found")
        }
        val req = HashMapStringAny()
        val res = HashMapStringAny()
        if( m.req != null ) {
            for( (k,v) <- m.req ) {
                req.put(k,parseRightValue(v,context))
            }
        }
        if( m.res != null ) {
            for( (k,v) <- m.res ) {
                res.put(k,parseRightValue(v,context))
            }
        }

        val key = serviceId+":"+msgId
        var buff = Router.main.mocks.getOrElse(key,null)
        if( buff == null ) {
            buff = ArrayBuffer[MockCfg]()
            Router.main.mocks.put(key,buff)
        }
        buff += new MockCfg(key,req,res)
        (true,"success")
    }

    def dump(global:TestCaseV2,testcases:ArrayBuffer[TestCaseV2]) {
        if( global != null ) {
            println(global.toString())
            println()
        }
        for( t <- testcases ) {
            println(t.toString())
            println()
        }
    }

    // 从全局上下文里解析值
    def parseRightValue(v:Any,context:HashMapStringAny):Any = {
        if( v == null || v == "" ) return v
        if( !v.isInstanceOf[String] ) return v
        val s = v.asInstanceOf[String]
        val nv = ValueParser.parse(s,null,context,false)

        if( nv == null || nv == "" ) return nv
        if( !nv.isInstanceOf[String] ) return nv
        val t = nv.asInstanceOf[String]

        //if( t.length >= 2 && t.startsWith("\"") &&  t.endsWith("\"") ) return t.substring(1,t.length-1)
        if( t.startsWith("[") && t.endsWith("]") ) return JsonCodec.parseArrayNotNull(t)
        if( t.startsWith("{") && t.endsWith("}") ) return JsonCodec.parseObjectNotNull(t)
        if( t.startsWith("s:")) return t.substring(2)
        if( t.startsWith("i:")) return t.substring(2).toInt
        t
    }

    // 从invoke结果集或context上下文里解析值
    def parseLeftValue(s:String,ret:HashMapStringAny,context:HashMapStringAny):String = {
        var a = ValueParser.parse(s,ret,context,true) // 先根据结果集解析, 再根据全局上下文解析
        if( a == null ) return null 
        a match {
            case m:HashMapStringAny =>
                JsonCodec.mkString(m)
            case a:ArrayBufferAny =>
                JsonCodec.mkString(a)
            case a:ArrayBufferString =>
                JsonCodec.mkString(a)
            case a:ArrayBufferInt =>
                JsonCodec.mkString(a)
            case a:ArrayBufferMap =>
                JsonCodec.mkString(a)
            case _ =>
                a.toString
        }
    }

    def parseFile(lines:ArrayBufferString):Tuple2[TestCaseV2,ArrayBuffer[TestCaseV2]] = {
        var i = 0
        
        var global:TestCaseV2 = null
        var testcases  = ArrayBuffer[TestCaseV2]()

        while( i < lines.size ){
            val t = lines(i)
            t match {
                case t if t.startsWith("global:") =>
                    val (l_global,nextLine) = parseTestCase("global",lines,i)
                    global = l_global
                    i = nextLine
                case t if t.startsWith("testcase:") =>
                    val (l_testcase,nextLine) = parseTestCase("testcase",lines,i)
                    testcases += l_testcase
                    i = nextLine
                case _ =>
                    println("line not valid: " + t)
                    return null
            }
        }

        (global,testcases)
    }

    def parseTestCase(tp:String,lines:ArrayBufferString,start:Int):Tuple2[TestCaseV2,Int] = {

        testCaseCount += 1
        var name = parseAttr(lines(start),"testcase")
        var enabled = parseAttr(lines(start),"enabled")
        var lineNo = parseAttr(lines(start),"lineNo")
        if( name == "" ) name = "testcase_"+testCaseCount
        if( tp == "global" ) name = "global"
        var i = start + 1

        val mocks = ArrayBuffer[TestCaseV2Invoke]()
        val setups = ArrayBuffer[TestCaseV2Invoke]()
        val teardowns = ArrayBuffer[TestCaseV2Invoke]()
        val asserts = ArrayBuffer[TestCaseV2Invoke]()
        val defines = ArrayBuffer[TestCaseV2Define]()

        var over = false
        while( i < lines.size && !over ){
            val t = lines(i)
            t match {
                case t if t.startsWith("define:") =>
                    defines += parseDefine(lines,i)
                    i += 1
                case t if t.startsWith("mock:") =>
                    mocks += parseInvoke("mock",lines,i)
                    i += 1
                case t if t.startsWith("setup:") =>
                    setups += parseInvoke("setup",lines,i)
                    i += 1
                case t if t.startsWith("teardown:") =>
                    teardowns += parseInvoke("teardown",lines,i)
                    i += 1
                case t if t.startsWith("assert:") =>
                    asserts += parseInvoke("assert",lines,i)
                    i += 1
                case t if t.startsWith("testcase:") =>
                    over = true
                case t if t.startsWith("global:") =>
                    over = true
                case _ =>
                    println("invalid line in testcase: " + t)
                    i += 1
            }
        }
        val o = new TestCaseV2(tp,name,mocks,setups,teardowns,asserts,defines)
        if( lineNo != "" ) o.lineNo = lineNo.toInt
        o.enabled = enabled.toLowerCase != "0" && enabled.toLowerCase != "false"
        (o,i)
    }

    def parseDefine(lines:ArrayBufferString,start:Int):TestCaseV2Define = {
        val line = lines(start)
        val p = parseDefine(line)
        val lineNo = parseAttr(line,"lineNo")
        val map = parseMap(p)
        val map2 = new LinkedHashMapStringAny()
        for( (k,v) <- map ) { 
            if( k.startsWith("$") )
                map2.put(k,v)
            else
                map2.put("$"+k,v)
        }
        val d = new TestCaseV2Define(map2)
        if( lineNo != "" ) d.lineNo = lineNo.toInt
        d
    }

    def parseInvoke(tp:String,lines:ArrayBufferString,start:Int):TestCaseV2Invoke = {
        val line = lines(start)
        val service = parseAttr(line,tp)
        val lineNo = parseAttr(line,"lineNo")
        var timeout = parseAttr(line,"timeout")
        if( timeout == "" ) timeout = "15000"
        var id = parseAttr(line,"id")
        if( id != "" && !id.startsWith("$") ) id = "$" + id
        val req = parseReq(line)
        val res = parseRes(line)
        val reqMap = parseMap(req)
        val resMap = parseMap(res)
        if( tp != "mock" && !resMap.contains(codeTag) ) resMap.put(codeTag,0)
        val t = new TestCaseV2Invoke(tp,service,timeout.toInt,reqMap,resMap,id)
        if( lineNo != "" ) t.lineNo = lineNo.toInt
        t
    }

    val sep1 = 1.toChar.toString // blank
    val sep2 = 2.toChar.toString // =

    val sep3 = 3.toChar // = in ""
    val sep4 = 4.toChar // blank in ""

    val r1 = """ ([^ =]+)=""".r

    def parseMap(s:String):LinkedHashMapStringAny = {
        val map = new LinkedHashMapStringAny()

        var ns = escape(s)
        ns = r1.replaceAllIn(" "+ns,(m)=>sep1+m.group(1).replace("$","\\$")+sep2) // $0 ... $9 有特殊含义，$需转义
        val ss = ns.split(sep1).map(_.trim)
        for( t <- ss ) { // if t.indexOf(sep2) > 0
            val tt = t.split(sep2)
            val key = parseKey(tt(0))
            if( tt.size >= 2 ) map.put(key,parseValue(tt(1)))
            else if( tt.size >= 1 && key != "" ) map.put(key,"")
        }
        map
    }

    def escape(s:String):String = {
        escape2(escape1(s))
    }

    def escape1(s:String):String = {
        var afterSlash = false
        var ts = ""
        for(c <- s) {
            if( afterSlash ) {
                c match {
                    case '=' =>
                        ts = ts + sep3
                    case _ =>
                        ts = ts + '\\'
                        ts = ts + c
                }
                afterSlash = false
            } else if( c == '\\')  { 
                afterSlash = true
            } else {
                ts = ts + c
            }
        }
        if( afterSlash )
            ts = ts + '\\'
        ts
    }
    def escape2(s:String):String = {
        var inQuota = false
        var ts = ""
        for(c <- s) {
            if( c == '\"')  { 
                inQuota = !inQuota
                ts = ts + "\""
            } else if( inQuota ) {
                c match {
                    case '=' =>
                        ts = ts + sep3
                    case _ =>
                        ts = ts + c
                }
            } else {
                ts = ts + c
            }
        }
        ts
    }

    def unescape(s:String):String = {
        var ts = ""
        for(ch <- s) {
            ch match {
                case c if c == sep3 =>
                    ts += "="
                case c =>
                    ts += c
            }
        }
        ts
    }

    def parseKey(s:String):String = {
        val t = unescape( s.trim() )
        t
    }

    def parseValue(s:String):Any = {
        val t = unescape( s.trim() )
        t
    }

    def parseDefine(l:String):String = {
        val p1 = l.indexOf("define:")
        if( p1 < 0 ) return ""
        l.substring(p1+7)
    }
    def parseReq(l:String):String = {
        val p1 = l.indexOf(" req:")
        if( p1 < 0 ) return ""
        val p2 = l.indexOf(" res:",p1+1)
        if( p2 < 0 ) return l.substring(p1+5)
        l.substring(p1+5,p2)
    }
    def parseRes(l:String):String = {
        val p1 = l.indexOf(" res:")
        if( p1 < 0 ) return ""
        l.substring(p1+5)
    }
    def parseAttr(s:String,field:String):String = {
        var l = s
        var p = l.indexOf("res:")
        if( p>=0 )  l = l.substring(0,p)
        p = l.indexOf("req:")
        if( p>=0 )  l = l.substring(0,p)
        val p1 = l.indexOf(field+":")
        if( p1 < 0 ) return ""
        val p2 = l.indexOf(" ",p1+1)
        if( p2 < 0 ) return l.substring(p1+field.length+1)
        l.substring(p1+field.length+1,p2)
    }

    def appendLineNo(line:String):String = {
        lineNoCnt += 1
        line.trim match {
            case l if l.startsWith("define:") =>
                appendLineNo(line,lineNoCnt)
            case l if l.startsWith("global:") =>
                appendLineNo(line,lineNoCnt)
            case l if l.startsWith("testcase:") =>
                appendLineNo(line,lineNoCnt)
            case l if l.startsWith("mock:") =>
                appendLineNo(line,lineNoCnt)
            case l if l.startsWith("setup:") =>
                appendLineNo(line,lineNoCnt)
            case l if l.startsWith("teardown:") =>
                appendLineNo(line,lineNoCnt)
            case l if l.startsWith("assert:") =>
                appendLineNo(line,lineNoCnt)
            case _ =>
                line
        }

    }

    def appendLineNo(line:String,n:Int):String = {
        val s = " lineNo:"+n+" "
        if( line.indexOf(" ") > 0 ) 
            line.replaceFirst(" ",s)
        else
            line + s
    }

    def mergeLines(lines:Buffer[String]):ArrayBufferString = {
        val buff = ArrayBufferString()
        for( i <- 0 until lines.size ){
            val t = lines(i)
            t.trim match {
                case l if l.startsWith("define:") =>
                    buff += l
                case l if l.startsWith("global:") =>
                    buff += l
                case l if l.startsWith("testcase:") =>
                    buff += l
                case l if l.startsWith("mock:") =>
                    buff += l
                case l if l.startsWith("setup:") =>
                    buff += l
                case l if l.startsWith("teardown:") =>
                    buff += l
                case l if l.startsWith("assert:") =>
                    buff += l
                case l if l.startsWith("#") =>
                    buff += l
                case _ =>
                    buff(buff.size-1) = buff(buff.size-1) + " " + t
            }
        }
        buff
    }

    def removeComment(line:String):String = {
        val p = line.lastIndexOf(" #")
        if( p >= 0 ) return line.substring(0,p)
        line
    }

    def isNewFormat(file:String):Boolean = {
        Source.fromFile(file,"UTF-8").getLines.map(_.trim).filter( l => l.startsWith("testcase:") || l.startsWith("global:")).size > 0
    }

}

class TestCaseV1(val serviceId:Int,val msgId:Int,val body:HashMapStringAny,val repeat:Int = 1,val xhead:HashMapStringAny = new HashMapStringAny() )

object TestCaseRunnerV1 {

    var requestCount = 1
    var replyCount = 0
    var soc : SocImpl = _

    val lock = new ReentrantLock(false)
    val replied = lock.newCondition()

    def main(args:Array[String]) {

        var max = 2000000
        var s = parseArg(args,"maxPackageSize")
        if( s != "" ) max = s.toInt

        var file = "."+File.separator+"testcase"+File.separator+"default.txt"
        s = parseFile(args)
        if( s != "" ) file = s
        println()
        println("testcase file:  " + file)

        val codecs = new TlvCodecs("."+File.separator+"avenue_conf")

        val in = new InputStreamReader(new FileInputStream("."+File.separator+"config.xml"),"UTF-8")
        val cfgXml = XML.load(in)
        val port = (cfgXml \ "SapPort").text.toInt
        val timeout = 15000
        var serverAddr = "127.0.0.1:"+port
        s = (cfgXml \ "TestServerAddr").text
        if( s != "" ) serverAddr = s

        soc = new SocImpl(serverAddr,codecs,callback,connSizePerAddr=1,maxPackageSize=max)

        val lines = Source.fromFile(file,"UTF-8").getLines.toList.filter( _.trim != "").filter( !_.startsWith("#") )

        val testcases = parseLines(lines)

        //val sendCount = testcases.foldLeft(0) { (sum,tc) => sum + tc.repeat }

        println("total test case: "+testcases.size)

        var seq = 0

        for( i <- 1 to testcases.size ) {

            val t = testcases(i-1)

            lock.lock();
            try {
                for( j <- 1 to t.repeat ) {
                    seq += 1

                    replyCount = 0

                    val req = new SocRequest(seq.toString,t.serviceId,t.msgId,t.body,AvenueCodec.ENCODING_UTF8,t.xhead)
                    println("req="+req)
                    soc.send(req,timeout)
                    if( replyCount < requestCount )
                        replied.await( timeout + 100, TimeUnit.MILLISECONDS )

                }
            } finally {
                lock.unlock();
            }

        }

        soc.close
    }

    def callback(any:Any){

        any match {

            case reqRes: SocRequestResponseInfo =>

                lock.lock();
                try {
                    val reqRes = any.asInstanceOf[SocRequestResponseInfo]
                    println("res="+reqRes.res)
                    replyCount += 1
                    if( replyCount >= requestCount )
                        replied.signal()
                } finally {
                    lock.unlock();
                }

            case ackInfo: SocRequestAckInfo =>

                println("ack="+ackInfo.req.requestId)

            case _ =>
        }
    }
    def parseLines(lines: List[String]) : ArrayBuffer[TestCaseV1] = {

        val testcases = new ArrayBuffer[TestCaseV1]()

        for( line <- lines ) {
            val tc = parseLine(line)
            if( tc != null )
                testcases += tc
        }

        testcases
    }

    def parseArg(args:Array[String],key:String):String = {
        for(i <- 0 until args.size) {
            if( args(i) == "--" + key && i + 1 < args.size ) {
                return args(i+1)
            }
            if( args(i) == "-" + key ) {
                return "1"
            }
        }
        ""
    }

    def parseFile(args:Array[String]):String = {
        var i = 0
        while(i < args.size) {
            if( args(i).startsWith("--") ) i += 2
            else if( args(i).startsWith("-") ) i += 1
            else return args(i)
        }
        ""
    }

    def parseLine(line:String) : TestCaseV1 = {

        val p1 = line.indexOf(",")
        if( p1 <= 0 ) return null

        val p2 = line.indexOf(",",p1+1)
        if( p2 <= 0 ) return null

        val serviceId = line.substring(0,p1).toInt
        val msgId = line.substring(p1+1,p2).toInt
        val json = line.substring(p2+1)

        val body = JsonCodec.parseObject(json)

        var repeat = body.i("x_repeat")
        if( repeat == 0 ) repeat = 1

        val xhead = HashMapStringAny()
        val socId = body.s("x_socId","")
        if( socId != "") xhead.put("socId",socId)
        val spsId = body.s("x_spsId","")
        if( spsId != "") xhead.put("spsId",spsId)
        val uniqueId = body.s("x_uniqueId","")
        if( uniqueId != "") xhead.put("uniqueId",uniqueId)
        val appId = body.s("x_appId","")
        if( appId != "") xhead.put("appId",appId)

        val tc = new TestCaseV1(serviceId,msgId,body,repeat,xhead)
        tc
    }

}

