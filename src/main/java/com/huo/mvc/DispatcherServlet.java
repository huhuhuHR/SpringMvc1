package com.huo.mvc;

import com.huo.mvc.annotation.*;
import com.sun.xml.internal.ws.org.objectweb.asm.*;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DispatcherServlet extends HttpServlet {
    // 扫描包下被@Controller,@Service注解的类
    private List<String> classNames = new ArrayList<String>();
    private Map<String, Object> instanceMapping = new HashMap<String, Object>();
    private Map<String, HandlerModel> handlerMapping = new HashMap<String, HandlerModel>();

    @Override
    public void init() throws ServletException {
        scanPackage(getInitParameter("scanPackage"));
        doInstance();
        doAutoWired();
        doHandlerMapping();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 根据请求的URL去查找对应的method
        try {
            boolean isMatcher = pattern(req, resp);
            if (!isMatcher) {
                out(resp, "404 not found");
            }
        } catch (Exception ex) {
            ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            ex.printStackTrace(new java.io.PrintWriter(buf, true));
            String expMessage = buf.toString();
            buf.close();
            out(resp, "500 Exception" + "\n" + expMessage);
        }
    }

    private void doHandlerMapping() {
        if (instanceMapping.isEmpty()) {
            return;
        }
        // 遍历托管的对象，寻找Controller
        for (Map.Entry<String, Object> entry : instanceMapping.entrySet()) {
            Class<?> cl = entry.getValue().getClass();
            // 只处理Controller的，只有Controller有RequestMapping
            if (!cl.isAnnotationPresent(Controller.class)) {
                continue;
            }
            // 定义url
            String url = "/";
            // 取到Controller上的RequestMapping值
            if (cl.isAnnotationPresent(RequestMapping.class)) {
                RequestMapping requestMapping = cl.getAnnotation(RequestMapping.class);
                url += requestMapping.value();
            }
            // 获取方法上的RequestMapping
            Method[] methods = cl.getMethods();
            // 只处理带RequestMapping的方法
            for (Method method : methods) {
                if (!method.isAnnotationPresent(RequestMapping.class)) {
                    continue;
                }
                RequestMapping methodMapping = method.getAnnotation(RequestMapping.class);
                // requestMapping.value()即是在requestMapping上注解的请求地址，不管用户写不写"/"，我们都给他补上
                String realUrl = url + "/" + methodMapping.value();
                // 替换掉多余的"/",因为有的用户在RequestMapping上写"/xxx/xx",有的不写，所以我们处理掉多余的"/"
                realUrl = realUrl.replaceAll("/+", "/");
                // 获取所有的参数的注解，有几个参数就有几个annotation[]，为毛是数组呢，因为一个参数可以有多个注解……
                Annotation[][] annotations = method.getParameterAnnotations();
                // 由于后面的Method的invoke时，需要传入所有参数的值的数组，所以需要保存各参数的位置
                /*
                 * 以Search方法的这几个参数为例 @RequestParam("name") String name, HttpServletRequest
                 * request, HttpServletResponse response 未来在invoke时，需要传入类似这样的一个数组["abc",
                 * request, response]。"abc"即是在Post方法中通过request.getParameter("name")来获取
                 * Request和response这个简单，在post方法中直接就有。 所以我们需要保存@RequestParam上的value值，和它的位置。譬如
                 * name->0,只有拿到了这两个值， 才能将post中通过request.getParameter("name")得到的值放在参数数组的第0个位置。
                 * 同理，也需要保存request的位置1，response的位置2
                 */
                Map<String, Integer> paramMap = new HashMap<String, Integer>();
                // 获取方法里的所有参数的参数名（注意：此处使用了ASM.jar
                // 版本为asm-3.3.1，需要在web-inf下建lib文件夹，引入asm-3.3.1.jar，自行下载）
                // 如Controller的add方法，将得到如下数组["name", "addr", "request", "response"]
                String[] paramNames = getMethodParameterNamesByAsm4(cl, method);
                // 获取所有参数的类型，提取Request和Response的索引
                Class<?>[] paramTypes = method.getParameterTypes();
                for (int i = 0; i < annotations.length; i++) {
                    // 获取每个参数上的所有注解
                    Annotation[] anns = annotations[i];
                    if (anns.length == 0) {
                        // 如果没有注解，则是如String abc，Request request这种，没写注解的
                        // 如果没被RequestParam注解
                        // 如果是Request或者Response，就直接用类名作key；如果是普通属性，就用属性名
                        Class<?> type = paramTypes[i];
                        if (type == HttpServletRequest.class || type == HttpServletResponse.class) {
                            paramMap.put(type.getName(), i);
                        } else {
                            // 参数没写@RequestParam注解，只写了String name，那么通过java是无法获取到name这个属性名的
                            // 通过上面asm获取的paramNames来映射
                            paramMap.put(paramNames[i], i);
                        }
                        continue;
                    }
                    // 有注解，就遍历每个参数上的所有注解
                    for (Annotation ans : anns) {
                        // 找到被RequestParam注解的参数，并取value值
                        if (ans.annotationType() == RequestParam.class) {
                            // 也就是@RequestParam("name")上的"name"
                            String paramName = ((RequestParam) ans).value();
                            // 如果@RequestParam("name")这里面
                            if (!"".equals(paramName.trim())) {
                                paramMap.put(paramName, i);
                            }
                        }
                    }
                }
                HandlerModel model = new HandlerModel(method, entry.getValue(), paramMap);
                handlerMapping.put(realUrl, model);
            }
        }

    }

    public static String[] getMethodParameterNamesByAsm4(final Class clazz, final Method method) {
        final String methodName = method.getName();
        final Class<?>[] methodParameterTypes = method.getParameterTypes();
        final int methodParameterCount = methodParameterTypes.length;
        String className = method.getDeclaringClass().getName();
        final boolean isStatic = Modifier.isStatic(method.getModifiers());
        final String[] methodParametersNames = new String[methodParameterCount];
        int lastDotIndex = className.lastIndexOf(".");
        className = className.substring(lastDotIndex + 1) + ".class";
        InputStream is = clazz.getResourceAsStream(className);
        try {
            ClassReader cr = new ClassReader(is);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cr.accept(new ClassAdapter(cw) {
                public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                        String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    final Type[] argTypes = Type.getArgumentTypes(desc);
                    if (!methodName.equals(name) || !matchTypes(argTypes, methodParameterTypes)) {
                        return mv;
                    }
                    return new MethodAdapter(mv) {
                        public void visitLocalVariable(String name, String desc, String signature, Label start,
                                Label end, int index) {
                            int methodParameterIndex = isStatic ? index : index - 1;
                            if (0 <= methodParameterIndex && methodParameterIndex < methodParameterCount) {
                                methodParametersNames[methodParameterIndex] = name;
                            }
                            super.visitLocalVariable(name, desc, signature, start, end, index);
                        }
                    };
                }
            }, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return methodParametersNames;
    }

    /**
     * 比较参数是否一致
     */
    private static boolean matchTypes(Type[] types, Class<?>[] parameterTypes) {
        if (types.length != parameterTypes.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            if (!Type.getType(parameterTypes[i]).equals(types[i])) {
                return false;
            }
        }
        return true;
    }

    private void doAutoWired() {
        if (instanceMapping.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : instanceMapping.entrySet()) {
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                if (!field.isAnnotationPresent(Autowired.class)) {
                    continue;
                }
                String beanName;
                Autowired autowired = field.getAnnotation(Autowired.class);
                if ("".equals(autowired.value())) {
                    beanName = lowerFirstChar(field.getType().getSimpleName());
                } else {
                    beanName = autowired.value();
                }
                field.setAccessible(true);
                if (instanceMapping.get(beanName) != null) {
                    try {
                        field.set(entry.getValue(), instanceMapping.get(beanName));
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 通过扫描到的含有指定注解的文件路径，逐个实例化对象
     */
    private void doInstance() {
        if (classNames.size() == 0) {
            return;
        }
        try {
            for (String className : classNames) {
                Class<?> cl = Class.forName(className);
                Object object = cl.newInstance();
                if (cl.isAnnotationPresent(Controller.class)) {
                    instanceMapping.put(lowerFirstChar(cl.getSimpleName()), object);
                    continue;
                }
                if (cl.isAnnotationPresent(Service.class)) {
                    Service service = cl.getAnnotation(Service.class);
                    String value = service.value();
                    // 通过类类型创建的对象
                    // @Service("xxx")则该对象的id就是xxx
                    if (!"".equals(value.trim())) {
                        instanceMapping.put(value.trim(), object);
                        continue;
                    }
                    // 如果没有指定service的id，以类的首字母小写的接口名字为id
                    Class[] inters = cl.getInterfaces();
                    for (Class c : inters) {
                        instanceMapping.put(lowerFirstChar(c.getSimpleName()), object);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 查询目录下及其所有子目录下迭代class文件中是否含有@Controller和@Service
     * 
     * 含有取该class的文件路径，后通过该路径创建对象
     * 
     * @param dirName
     */
    private void scanPackage(String dirName) {
        // path不以’/'开头时，默认是从此类所在的包下取资源；
        // path 以’/'开头时，则是从ClassPath根下获取；
        String relativePath = "/" + dirName.replaceAll("\\.", "/");
        URL url = getClass().getClassLoader().getResource(relativePath);
        if (url == null) {
            throw new RuntimeException("初始化失败");
        }
        File dirFile = new File(url.getFile());
        // 获取当前夹子下面所有文件
        File[] files = dirFile.listFiles();
        if (files == null) {
            return;
        }
        // 遍历目录下的文件
        for (File file : files) {
            // 获取当前查看的文件
            String fileName = file.getName();
            // 是目录，迭代查询包下文件
            if (file.isDirectory()) {
                // 拼接新目录
                String newDirName = dirName + "." + fileName;
                // 扫描目录
                scanPackage(newDirName);
                continue;
            }
            // 不是文件夹也不是编译好的class文件，跳过该文件
            if (!fileName.endsWith(".class")) {
                continue;
            }
            // 去掉当前文件的后缀
            String className = dirName + "." + fileName.replace(".class", "");
            try {
                // 通过文件位置获得文件类类型
                Class<?> cl = Class.forName(className);
                // 判断文件是否含有Controller或者Service注解
                if (cl.isAnnotationPresent(Controller.class) || cl.isAnnotationPresent(Service.class)) {
                    classNames.add(className);
                }
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    private String lowerFirstChar(String className) {
        char[] chars = className.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

    private boolean pattern(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (handlerMapping.isEmpty()) {
            return false;
        }
        // 用户请求地址
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        // 用户写了多个"///"，只保留一个
        requestUri = requestUri.replace(contextPath, "").replaceAll("/+", "/").replace(".do", "").replace(".htm", "");
        String router;
        // 遍历HandlerMapping，寻找url匹配的
        for (Map.Entry<String, HandlerModel> entry : handlerMapping.entrySet()) {
            router = entry.getKey();
            if (!router.equals(requestUri)) {
                continue;
            }
            // 取出对应的HandlerModel
            HandlerModel handlerModel = entry.getValue();
            Object[] paramValues = prePareParams(request, response, handlerModel);
            // 激活该方法
            handlerModel.method.invoke(handlerModel.controller, paramValues);
            return true;
        }

        return false;
    }

    private Object[] prePareParams(HttpServletRequest request, HttpServletResponse response,
            HandlerModel handlerModel) {
        // 方法参数映射名以及参数所处位置
        Map<String, Integer> paramIndexMap = handlerModel.paramMap;
        if (paramIndexMap.size() == 0) {
            return new Object[0];
        }
        // 方法所含参数,用来实际给参数赋值
        Class<?>[] types = handlerModel.method.getParameterTypes();
        // 定义一个数组来保存应该给method的所有参数赋值的数组，临时变量
        Object[] paramValues = new Object[paramIndexMap.size()];
        // 遍历一个方法的所有参数[name->0,addr->1,HttpServletRequest->2]
        String key;
        int index;
        for (Map.Entry<String, Integer> param : paramIndexMap.entrySet()) {
            key = param.getKey();
            index = param.getValue();
            if (key.equals(HttpServletRequest.class.getName())) {
                paramValues[index] = request;
            } else if (key.equals(HttpServletResponse.class.getName())) {
                paramValues[index] = response;
            } else {
                // 如果用户传了参数，譬如 name= "wolf"，做一下参数类型转换，将用户传来的值转为方法中参数的类型
                String parameter = request.getParameter(key);
                if (parameter != null) {
                    paramValues[index] = convert(parameter.trim(), types[index]);
                }
            }
        }
        return paramValues;
    }

    /**
     * 将用户传来的参数转换为方法需要的参数类型
     */
    private Object convert(String parameter, Class<?> targetType) {
        if (targetType == String.class) {
            return parameter;
        } else if (targetType == Integer.class || targetType == int.class) {
            return Integer.valueOf(parameter);
        } else if (targetType == Long.class || targetType == long.class) {
            return Long.valueOf(parameter);
        } else if (targetType == Boolean.class || targetType == boolean.class) {
            if (parameter.toLowerCase().equals("true") || parameter.equals("1")) {
                return true;
            } else if (parameter.toLowerCase().equals("false") || parameter.equals("0")) {
                return false;
            }
            throw new RuntimeException("不支持的参数");
        } else {
            return null;
        }
    }

    private void out(HttpServletResponse response, String result) {
        try {
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().print(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class HandlerModel {
        Method method;
        Object controller;
        Map<String, Integer> paramMap;

        public HandlerModel(Method method, Object controller, Map<String, Integer> paramMap) {
            this.method = method;
            this.controller = controller;
            this.paramMap = paramMap;
        }
    }

}
