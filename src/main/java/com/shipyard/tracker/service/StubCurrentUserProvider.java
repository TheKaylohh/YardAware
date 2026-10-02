package com.shipyard.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StubCurrentUserProvider implements CurrentUserProvider {

    private final String user;

    public StubCurrentUserProvider(@Value("${shipyard.demo-user:demo.user}") String user) {
        this.user = user;
    }

    @Override
    public String currentUser() {
        return user;
    }
}
