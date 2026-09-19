package com.megagraph.shop.config;

import com.megagraph.shop.catalog.Category;
import com.megagraph.shop.catalog.CategoryRepository;
import com.megagraph.shop.identity.Merchant;
import com.megagraph.shop.identity.MerchantRepository;
import com.megagraph.shop.identity.MerchantRole;
import com.megagraph.shop.identity.MerchantUser;
import com.megagraph.shop.identity.MerchantUserRepository;
import com.megagraph.shop.identity.UserAccount;
import com.megagraph.shop.identity.UserAccountRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Configuration
public class DevSeedConfig {

    @Bean
    @ConditionalOnProperty(name = "app.dev-seed.enabled", havingValue = "true")
    ApplicationRunner devDataInitializer(DevSeedService devSeedService) {
        return args -> devSeedService.ensureSeedData();
    }

    @Bean
    DevSeedService devSeedService(
            UserAccountRepository users,
            MerchantRepository merchants,
            MerchantUserRepository memberships,
            CategoryRepository categories,
            PasswordEncoder passwordEncoder
    ) {
        return new DevSeedService(users, merchants, memberships, categories, passwordEncoder);
    }

    static class DevSeedService {
        private final UserAccountRepository users;
        private final MerchantRepository merchants;
        private final MerchantUserRepository memberships;
        private final CategoryRepository categories;
        private final PasswordEncoder passwordEncoder;

        DevSeedService(
                UserAccountRepository users,
                MerchantRepository merchants,
                MerchantUserRepository memberships,
                CategoryRepository categories,
                PasswordEncoder passwordEncoder
        ) {
            this.users = users;
            this.merchants = merchants;
            this.memberships = memberships;
            this.categories = categories;
            this.passwordEncoder = passwordEncoder;
        }

        @Transactional
        public void ensureSeedData() {
            if (users.findByUsernameIgnoreCase("merchant@megagraph.local").isEmpty()) {
                UserAccount user = users.save(new UserAccount(
                        "merchant@megagraph.local",
                        passwordEncoder.encode("merchant123"),
                        "示例商家"
                ));
                Merchant merchant = merchants.save(new Merchant("MegaGraph 示例店铺"));
                memberships.save(new MerchantUser(merchant.getId(), user.getId(), MerchantRole.OWNER));
            }

            if (users.findByUsernameIgnoreCase("shopper@megagraph.local").isEmpty()) {
                users.save(new UserAccount(
                        "shopper@megagraph.local",
                        passwordEncoder.encode("shopper123"),
                        "示例顾客"
                ));
            }

            if (categories.count() == 0) {
                Category apparel = categories.save(new Category(null, "服饰", 1));
                categories.save(new Category(apparel.getId(), "上衣", 2));
                categories.save(new Category(apparel.getId(), "外套", 2));
            }
        }
    }
}
