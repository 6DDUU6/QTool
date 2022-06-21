package cc.hicore.HookItemLoader.core;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;

import cc.hicore.ConfigUtils.GlobalConfig;
import cc.hicore.DexFinder.DexFinder;
import cc.hicore.HookItemLoader.bridge.BaseFindMethodInfo;
import cc.hicore.HookItemLoader.bridge.BaseMethodInfo;
import cc.hicore.HookItemLoader.bridge.CommonMethodInfo;
import cc.hicore.HookItemLoader.bridge.FindMethodByName;
import cc.hicore.HookItemLoader.bridge.FindMethodInvokingMethod;
import cc.hicore.HookItemLoader.bridge.FindMethodsWhichInvokeMethod;
import cc.hicore.ReflectUtils.MClass;
import cc.hicore.ReflectUtils.MMethod;
import cc.hicore.Utils.Utils;
import cc.hicore.qtool.BuildConfig;
import cc.hicore.qtool.HookEnv;
import cc.hicore.qtool.XposedInit.HostInfo;
import de.robv.android.xposed.XposedBridge;

public class MethodScannerWorker {
    public static class ScannerLink{
        public CoreLoader.XPItemInfo item;
        public String ID;
        public BaseMethodInfo Info;
        public ArrayList<ScannerLink> LinkingID;
        public ScannerLink LinkToID;
    }
    public static boolean checkIsAvailable(){
        String cacheVer = HostInfo.getVersion() + "."+HostInfo.getVerCode() + "->" + BuildConfig.VERSION_CODE;
        if (GlobalConfig.Get_String("cacheVer").equals(cacheVer)){
            return true;
        }
        for (CoreLoader.XPItemInfo info : CoreLoader.clzInstance.values()){
            XposedBridge.log(info.isVersionAvailable + ":" + info.NeedMethodInfo);
            if (info.isVersionAvailable && info.NeedMethodInfo != null){
                for (BaseMethodInfo methodInfo : info.NeedMethodInfo){
                    if (methodInfo instanceof CommonMethodInfo) continue;
                    return false;
                }
            }
        }
        return true;
    }
    private static final ArrayList<ScannerLink> rootNode = new ArrayList<>();

    private static void CollectLinkInfo(){
        ArrayList<BaseMethodInfo> allFindMethodInfo = new ArrayList<>();
        for (CoreLoader.XPItemInfo item : CoreLoader.clzInstance.values()){
            if (item.NeedMethodInfo != null){
                allFindMethodInfo.addAll(item.NeedMethodInfo);
            }
        }
        //编号查找的Link
        int restLink = 0;
        while (restLink != allFindMethodInfo.size()){
            allFindMethodInfo.removeIf(MethodScannerWorker::checkAndAddItemToTree);
        }
    }
    private static boolean checkAndAddItemToTree(BaseMethodInfo info){
        if (info instanceof CommonMethodInfo){
            ScannerLink newNode = new ScannerLink();
            newNode.ID = info.id;
            newNode.LinkingID = new ArrayList<>();
            newNode.Info = info;
            newNode.item = info.bandToInfo;
            rootNode.add(newNode);
            return true;
        }
        if (info instanceof BaseFindMethodInfo){
            if (((BaseFindMethodInfo) info).LinkedToMethodID == null){
                ScannerLink newNode = new ScannerLink();
                newNode.ID = info.id;
                newNode.LinkingID = new ArrayList<>();
                newNode.Info = info;
                newNode.item = info.bandToInfo;
                rootNode.add(newNode);
                return true;
            }else {
                String LinkedID = ((BaseFindMethodInfo) info).LinkedToMethodID;
                for (ScannerLink lnk : rootNode){
                    ScannerLink searchResult = searchNode(lnk,LinkedID);
                    if (searchResult != null){
                        ScannerLink newNode = new ScannerLink();
                        newNode.ID = info.id;
                        newNode.LinkingID = new ArrayList<>();
                        newNode.Info = info;
                        newNode.LinkToID = searchResult;
                        newNode.item = info.bandToInfo;
                        searchResult.LinkingID.add(newNode);
                        return true;
                    }
                }
            }
        }
        return false;
    }
    private static ScannerLink searchNode(ScannerLink link,String ID){
        if (link.ID.equals(ID))return link;
        if (link.LinkingID.size() > 0){
            for (ScannerLink childNode : link.LinkingID){
                ScannerLink result = searchNode(childNode,ID);
                if (result != null)return result;
            }
        }
        return null;
    }
    @SuppressLint({"ResourceType", "SetTextI18n"})
    public static void doFindMethod(){
        CollectLinkInfo();
        Utils.PostToMain(()->{
            Context context = Utils.getTopActivity();

            ScrollView sc = new ScrollView(context);
            sc.setBackgroundColor(Color.WHITE);
            LinearLayout mRoot = new LinearLayout(context);
            mRoot.setOrientation(LinearLayout.VERTICAL);
            sc.addView(mRoot);
            Dialog dialog = new Dialog(context,3);
            dialog.setCancelable(false);
            dialog.setContentView(sc);


            ArrayList<ScannerLink> sortedLinkScannerInfo = new ArrayList<>();
            for (ScannerLink node : rootNode){
                getSortedLinkInfo(sortedLinkScannerInfo,node);
            }
            ArrayList<TextView> nodeList = new ArrayList<>();
            for (ScannerLink node : sortedLinkScannerInfo){
                TextView view = new TextView(context);
                view.setGravity(Gravity.CENTER);
                view.setText(node.ID);
                view.setTextSize(16);
                view.setTextColor(Color.BLACK);
                view.setTag(node);
                LinearLayout.LayoutParams param = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                param.topMargin = Utils.dip2px(context,12);
                mRoot.addView(view,param);
                nodeList.add(view);
            }
            dialog.show();
            new Thread(()->{
                for (TextView nodeView : nodeList){
                    ScannerLink node = (ScannerLink) nodeView.getTag();
                    Utils.PostToMain(()->nodeView.setTextColor(Color.BLUE));
                    try{
                        BaseMethodInfo info = node.Info;
                        Method findResult = findMethod(info);
                        if (findResult == null){
                            nodeView.setTextColor(Color.RED);
                        }else {
                            info.bandToInfo.scanResult.add(findResult);
                            Utils.PostToMain(()->{
                                nodeView.setTextColor(Color.GREEN);
                                nodeView.setText(info.id + "\n↓\n"+
                                        findResult.getDeclaringClass().getName()+"."+findResult.getName());
                            });
                        }
                    }catch (Exception e){
                        nodeView.setTextColor(Color.RED);
                    }
                }
                Utils.ShowToastL("重启QQ以正常使用模块");
            },"QTool_Method_Finder").start();
        });

    }
    private static Method findMethod(BaseMethodInfo info){
        if (info instanceof CommonMethodInfo){
            return (Method) ((CommonMethodInfo) info).methods;
        }
        if (info instanceof FindMethodByName){
            FindMethodByName newNode = (FindMethodByName) info;
            Method[] findResult = DexFinder.getInstance().findMethodByString(newNode.name);
            for (Method m : findResult){
                if (newNode.checker.onMethod(m))return m;
            }
        }
        if (info instanceof FindMethodInvokingMethod){
            FindMethodInvokingMethod newNode = (FindMethodInvokingMethod) info;
            Method linkNode;
            if (newNode.checkMethod != null){
                linkNode = (Method) newNode.checkMethod;
            }else if (newNode.LinkedToMethodID != null){
                linkNode = getMethodFromCache(newNode.LinkedToMethodID);
            }else {
                return null;
            }
            Method[] findResult = DexFinder.getInstance().findMethodInvoking(linkNode);
            for (Method m : findResult){
                if (newNode.checker.onMethod(m))return m;
            }
        }
        if (info instanceof FindMethodsWhichInvokeMethod){
            FindMethodsWhichInvokeMethod newNode = (FindMethodsWhichInvokeMethod) info;
            Method linkNode;
            if (newNode.checkMethod != null){
                linkNode = (Method) newNode.checkMethod;
            }else if (newNode.LinkedToMethodID != null){
                linkNode = getMethodFromCache(newNode.LinkedToMethodID);
            }else {
                return null;
            }
            Method[] findResult = DexFinder.getInstance().findMethodInvokeTarget(linkNode);
            for (Method m : findResult){
                if (newNode.checker.onMethod(m))return m;
            }
        }
        return null;
    }
    public static Method getMethodFromCache(String ID){
        SharedPreferences share = HookEnv.AppContext.getSharedPreferences("m_info",0);
        String s = share.getString(ID,null);
        if (s == null)return null;
        return DescToMethod(s);
    }
    private static Method DescToMethod(String desc){
        try{
            JSONObject json = new JSONObject(desc);
            Class<?> clz = MClass.loadClass(json.getString("clz"));
            Class<?> returnType = MClass.loadClass(json.getString("retName"));

            JSONArray paramArr = json.getJSONArray("params");
            Class<?>[] paramArrClz = new Class<?>[paramArr.length()];
            for (int i=0;i<paramArr.length();i++){
                paramArrClz[i] = MClass.loadClass(paramArr.getString(i));
            }
            return MMethod.FindMethod(clz,json.getString("name"),returnType,paramArrClz);
        }catch (Exception e){
            return null;
        }
    }
    private static String getMethodDesc(Method m){
        try{
            JSONObject newJson = new JSONObject();
            newJson.put("clz",m.getDeclaringClass().getName());
            newJson.put("name",m.getName());
            newJson.put("retName",m.getReturnType().getName());

            JSONArray paramTypes = new JSONArray();
            for (Class<?> clz : m.getParameterTypes()){
                paramTypes.put(clz.getName());
            }
            newJson.put("params",paramTypes);
            return newJson.toString();
        }catch (Exception e){
            return "";
        }

    }
    private static void getSortedLinkInfo(ArrayList<ScannerLink> linkData,ScannerLink link){
        linkData.add(link);
        for (ScannerLink newNode : link.LinkingID){
            getSortedLinkInfo(linkData,newNode);
        }
    }
}