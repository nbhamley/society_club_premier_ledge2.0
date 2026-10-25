package com.scpl;

import com.scpl.dto.NewAdminRequest;
import com.scpl.dto.SocietyRequest;
import com.scpl.model.Society;
import com.scpl.model.User;
import com.scpl.repo.UserRepository;
import com.scpl.service.SocietyService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** Creates a brand-new, activated society with N admins, so a test that changes data never touches the seeded ones. */
public abstract class TenantFixtures extends IntegrationTestBase {

    public record Fixture(Society society, List<User> admins) {
        public Long id() {
            return society.getId();
        }
    }

    @Autowired
    protected SocietyService societyService;
    @Autowired
    protected UserRepository users;

    /** adminCount must be at least 3 (activation rule). Admins are fx{tag}.a1 ... with password Passw0rd!Fx. */
    protected Fixture newSociety(int adminCount) {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        User platform = users.findByUsernameIgnoreCase("platformadmin").orElseThrow();
        List<NewAdminRequest> requests = IntStream.rangeClosed(1, adminCount)
                .mapToObj(i -> new NewAdminRequest("fx" + tag + ".a" + i, "fx" + tag + ".a" + i + "@fixture.example",
                        "Fixture Admin " + i, "98200 0000" + i, "Passw0rd!Fx")).toList();
        Society created = societyService.createWithAdmins(
                new SocietyRequest("Fixture " + tag, "fx-" + tag, null, "Pune", null, null, null), requests, platform);
        societyService.activate(created.getId(), platform);
        List<User> admins = IntStream.rangeClosed(1, adminCount)
                .mapToObj(i -> users.findByUsernameIgnoreCase("fx" + tag + ".a" + i).orElseThrow()).toList();
        return new Fixture(societyService.get(created.getId()), admins);
    }
}