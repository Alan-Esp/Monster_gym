package com.monstergym;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "app.db.path=./target/context-test.db")
class MonsterGymApplicationTests {

    @Test
    void contextLoads() {
    }
}
