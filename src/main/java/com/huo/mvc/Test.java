package com.huo.mvc;

import com.huo.mvc.annotation.Controller;
import com.huo.mvc.annotation.Service;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Test {
    public static void main(String[] args) throws Exception {
        List<String> names = new ArrayList<String>();
        URL url = Test.class.getClassLoader().getResource("com/huo/mvc/controller");
        assert url != null;
        String path = url.getFile();
        System.out.println(path);
        File[] files = new File(path).listFiles();
        assert files != null;
        for (File file : files) {
            String absolutePath = "com.huo.mvc.controller" + "." + file.getName().replace(".class", "");
            System.out.println(absolutePath);
            Class<?> cl = Class.forName(absolutePath);
            if (cl.isAnnotationPresent(Controller.class) || cl.isAnnotationPresent(Service.class)) {
                names.add(absolutePath);
            }
        }
        System.out.println(Arrays.toString(names.toArray()));
    }

    @org.junit.Test
    public void test() throws Exception {
        Class<?> cl = Class.forName("com.huo.mvc.utils.Person");
        assert cl.newInstance() != cl.newInstance();
        Field field = cl.getDeclaredField("son");
        System.out.println(lowerFirstChar(field.getType().getSimpleName()));
    }

    private String lowerFirstChar(String className) {
        char[] chars = className.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }
}
