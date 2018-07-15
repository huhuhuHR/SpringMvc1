package com.huo.mvc.service.impl;

import com.huo.mvc.annotation.Service;
import com.huo.mvc.service.QueryService;

@Service("myQueryService")
public class QueryServiceImpl implements QueryService {
    @Override
    public String search(String name) {
        return "invoke search name = " + name;
    }
}
