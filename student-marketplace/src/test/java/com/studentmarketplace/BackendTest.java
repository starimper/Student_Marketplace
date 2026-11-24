package com.studentmarketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.DisplayName.class)
class BackendTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    // ═══════════════════════════════════════════════════════════
    //  TEST 1 – Register with a university e-mail succeeds
    // ═══════════════════════════════════════════════════════════
    @Test
    void test01_registerWithUniversityEmailReturnsToken() throws Exception {
        String email = uid() + "@kbtu.kz";
        MvcResult res = mvc.perform(post("/api/client/auth/register").contentType(JSON)
                        .content(om.writeValueAsString(Map.of(
                                "email", email, "password", "Password123", "name", "Student One"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn();
        String token = om.readTree(res.getResponse().getContentAsString()).get("token").asText();
        assertFalse(token.isBlank(), "JWT token must not be blank");
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 2 – Non-university e-mail is rejected (400)
    // ═══════════════════════════════════════════════════════════
    @Test
    void test02_registerWithGmailIsRejected() throws Exception {
        mvc.perform(post("/api/client/auth/register").contentType(JSON)
                        .content(om.writeValueAsString(Map.of(
                                "email", "someone@gmail.com",
                                "password", "Password123", "name", "Outsider"))))
                .andExpect(status().isBadRequest());
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 3 – Duplicate e-mail registration returns 409
    // ═══════════════════════════════════════════════════════════
    @Test
    void test03_duplicateEmailIsConflict() throws Exception {
        String email = uid() + "@kbtu.kz";
        register(email);
        mvc.perform(post("/api/client/auth/register").contentType(JSON)
                        .content(om.writeValueAsString(Map.of(
                                "email", email.toUpperCase(),   // case-insensitive check
                                "password", "Password123", "name", "Again"))))
                .andExpect(status().isConflict());
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 4 – Login with wrong password returns 401
    // ═══════════════════════════════════════════════════════════
    @Test
    void test04_wrongPasswordReturns401() throws Exception {
        String email = uid() + "@kbtu.kz";
        register(email);
        mvc.perform(post("/api/client/auth/login").contentType(JSON)
                        .content(om.writeValueAsString(Map.of("email", email, "password", "wrongPass!"))))
                .andExpect(status().isUnauthorized());
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 5 – /me endpoint requires a valid JWT
    // ═══════════════════════════════════════════════════════════
    @Test
    void test05_meEndpointRequiresAuth() throws Exception {
        // No token → 401
        mvc.perform(get("/api/client/auth/me"))
                .andExpect(status().isUnauthorized());

        // Valid token → 200 with correct e-mail
        String email = uid() + "@kbtu.kz";
        String token = register(email);
        mvc.perform(get("/api/client/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 6 – Create a listing and verify response shape
    // ═══════════════════════════════════════════════════════════
    @Test
    void test06_createListingReturnsCorrectShape() throws Exception {
        String token = register(uid() + "@kbtu.kz");
        MvcResult res = mvc.perform(post("/api/client/listings").contentType(JSON)
                        .header("Authorization", bearer(token))
                        .content(om.writeValueAsString(Map.of(
                                "title", "Linear Algebra book",
                                "description", "Great condition",
                                "price", 3500,
                                "category", "textbooks",
                                "condition", "good"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.price").value(3500))
                .andExpect(jsonPath("$.category").value("textbooks"))
                .andReturn();
        String id = om.readTree(res.getResponse().getContentAsString()).get("id").asText();
        assertTrue(id.startsWith("L-"), "Listing ID should start with 'L-'");
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 7 – Create listing without auth returns 401
    // ═══════════════════════════════════════════════════════════
    @Test
    void test07_createListingWithoutAuthReturns401() throws Exception {
        mvc.perform(post("/api/client/listings").contentType(JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 8 – Update listing by owner succeeds; by other user is 403
    // ═══════════════════════════════════════════════════════════
    @Test
    void test08_onlyOwnerCanUpdateListing() throws Exception {
        String owner = register(uid() + "@kbtu.kz");
        String other = register(uid() + "@kbtu.kz");
        String id = createListing(owner, "Telescope", 15000, "electronics");

        Map<String, Object> body = Map.of("title", "Telescope v2", "price", 14000,
                "category", "electronics", "condition", "good");

        // Another user gets 403
        mvc.perform(put("/api/client/listings/" + id).contentType(JSON)
                        .header("Authorization", bearer(other))
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        // Owner succeeds
        mvc.perform(put("/api/client/listings/" + id).contentType(JSON)
                        .header("Authorization", bearer(owner))
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(14000));
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 9 – Delete listing by owner returns {success:true}; by other returns 403
    // ═══════════════════════════════════════════════════════════
    @Test
    void test09_deleteListingOwnershipRules() throws Exception {
        String owner = register(uid() + "@kbtu.kz");
        String other = register(uid() + "@kbtu.kz");
        String id = createListing(owner, "Old lamp", 500, "dorm_home");

        mvc.perform(delete("/api/client/listings/" + id)
                        .header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/client/listings/" + id)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Deleted listing is not found in the catalog
        mvc.perform(get("/api/client/listings/" + id)
                        .header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 10 – Public catalog: search by keyword and price filter
    // ═══════════════════════════════════════════════════════════
    @Test
    void test10_publicSearchByKeywordAndMaxPrice() throws Exception {
        String seller = register(uid() + "@kbtu.kz");
        String word = "UniqueBook" + uid();
        createListing(seller, word + " cheap", 2000, "textbooks");
        createListing(seller, word + " expensive", 50000, "textbooks");

        // No auth required for GET /api/client/listings
        mvc.perform(get("/api/client/listings")
                        .param("q", word))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        mvc.perform(get("/api/client/listings")
                        .param("q", word)
                        .param("maxPrice", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value(word + " cheap"));
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 11 – Search returns page metadata (page, pageSize, total)
    // ═══════════════════════════════════════════════════════════
    @Test
    void test11_searchPageMetadataIsCorrect() throws Exception {
        mvc.perform(get("/api/client/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(12))
                .andExpect(jsonPath("$.total").isNumber());
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 12 – Invalid sort parameter returns 400
    // ═══════════════════════════════════════════════════════════
    @Test
    void test12_invalidSortParameterReturns400() throws Exception {
        mvc.perform(get("/api/client/listings").param("sort", "hack_field"))
                .andExpect(status().isBadRequest());
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 13 – Buy listing: success + duplicate buy returns 409
    // ═══════════════════════════════════════════════════════════
    @Test
    void test13_purchaseWorkflowAndDuplicateBuyIs409() throws Exception {
        String seller = register(uid() + "@kbtu.kz");
        String buyer1 = register(uid() + "@kbtu.kz");
        String buyer2 = register(uid() + "@kbtu.kz");
        String id = createListing(seller, "Concert ticket", 9000, "other");

        // First buyer succeeds
        mvc.perform(post("/api/listings/" + id + "/purchase")
                        .header("Authorization", bearer(buyer1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.listingId").value(id))
                .andExpect(jsonPath("$.finalPriceKzt").value(9000));

        // Second buyer gets 409
        mvc.perform(post("/api/listings/" + id + "/purchase")
                        .header("Authorization", bearer(buyer2)))
                .andExpect(status().isConflict());

        // Listing is now SOLD
        mvc.perform(get("/api/client/listings/" + id)
                        .header("Authorization", bearer(buyer2)))
                .andExpect(jsonPath("$.status").value("sold"));
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 14 – Seller cannot buy their own listing (400)
    // ═══════════════════════════════════════════════════════════
    @Test
    void test14_sellerCannotBuyOwnListing() throws Exception {
        String seller = register(uid() + "@kbtu.kz");
        String id = createListing(seller, "My own item", 1000, "other");

        mvc.perform(post("/api/listings/" + id + "/purchase")
                        .header("Authorization", bearer(seller)))
                .andExpect(status().isBadRequest());
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 15 – SOLD listing cannot be updated or deleted by seller
    // ═══════════════════════════════════════════════════════════
    @Test
    void test15_soldListingCannotBeModified() throws Exception {
        String seller = register(uid() + "@kbtu.kz");
        String buyer = register(uid() + "@kbtu.kz");
        String id = createListing(seller, "Signed book", 2000, "textbooks");

        // Buy it
        mvc.perform(post("/api/listings/" + id + "/purchase")
                        .header("Authorization", bearer(buyer)))
                .andExpect(status().isCreated());

        // Seller tries to update → 409
        mvc.perform(put("/api/client/listings/" + id).contentType(JSON)
                        .header("Authorization", bearer(seller))
                        .content(om.writeValueAsString(Map.of("title", "Changed", "price", 1,
                                "category", "textbooks", "condition", "good"))))
                .andExpect(status().isConflict());

        // Seller tries to delete → 409
        mvc.perform(delete("/api/client/listings/" + id)
                        .header("Authorization", bearer(seller)))
                .andExpect(status().isConflict());
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 16 – PATCH /status: invalid value is rejected; "sold" hides it from the catalog
    // ═══════════════════════════════════════════════════════════
    @Test
    void test16_patchStatusValidatesValueAndMarksSold() throws Exception {
        String seller = register(uid() + "@kbtu.kz");
        String title = "Keyboard" + uid();
        String id = createListing(seller, title, 12000, "electronics");

        // Unknown status value → 400
        mvc.perform(patch("/api/client/listings/" + id + "/status").contentType(JSON)
                        .header("Authorization", bearer(seller))
                        .content("{\"status\":\"paused\"}"))
                .andExpect(status().isBadRequest());

        // Mark sold outside the app
        mvc.perform(patch("/api/client/listings/" + id + "/status").contentType(JSON)
                        .header("Authorization", bearer(seller))
                        .content("{\"status\":\"sold\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sold"));

        // Gone from the public catalog
        mvc.perform(get("/api/client/listings").param("q", title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 17 – Favorites: toggle, check state, list, remove
    // ═══════════════════════════════════════════════════════════
    @Test
    void test17_favoritesToggleAndList() throws Exception {
        String seller = register(uid() + "@kbtu.kz");
        String fan = register(uid() + "@kbtu.kz");
        String id = createListing(seller, "Cool poster", 800, "other");

        // Toggle on
        mvc.perform(post("/api/client/users/me/favorites/" + id + "/toggle")
                        .header("Authorization", bearer(fan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isFavorite").value(true));

        // Check individual state
        mvc.perform(get("/api/client/users/me/favorites/" + id)
                        .header("Authorization", bearer(fan)))
                .andExpect(jsonPath("$.isFavorite").value(true));

        // Appears in list
        mvc.perform(get("/api/client/users/me/favorites")
                        .header("Authorization", bearer(fan)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));

        // Toggle off
        mvc.perform(post("/api/client/users/me/favorites/" + id + "/toggle")
                        .header("Authorization", bearer(fan)))
                .andExpect(jsonPath("$.isFavorite").value(false));

        // List is now empty
        mvc.perform(get("/api/client/users/me/favorites")
                        .header("Authorization", bearer(fan)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 18 – User profile: patch own profile; cannot patch another's
    // ═══════════════════════════════════════════════════════════
    @Test
    void test18_profilePatchOwnershipRules() throws Exception {
        String userA = register(uid() + "@kbtu.kz");
        String userB = register(uid() + "@kbtu.kz");

        // Patch own profile
        mvc.perform(patch("/api/client/users/me").contentType(JSON)
                        .header("Authorization", bearer(userA))
                        .content("{\"faculty\":\"CS\",\"year\":2,\"telegram\":\"@student_a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.faculty").value("CS"))
                .andExpect(jsonPath("$.year").value(2))
                .andExpect(jsonPath("$.telegram").value("@student_a"));

        // Get userA's id to try to edit from userB
        String userId = om.readTree(
                mvc.perform(get("/api/client/auth/me").header("Authorization", bearer(userA)))
                        .andReturn().getResponse().getContentAsString()
        ).get("id").asText();

        // Patch another user's profile → 403
        mvc.perform(patch("/api/client/users/" + userId).contentType(JSON)
                        .header("Authorization", bearer(userB))
                        .content("{\"faculty\":\"Hacked\"}"))
                .andExpect(status().isForbidden());
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 19 – Admin: deactivate user, then login fails; student cannot call admin API
    // ═══════════════════════════════════════════════════════════
    @Test
    void test19_adminDeactivatesUserAndStudentCannotUseAdminApi() throws Exception {
        String admin = adminToken();
        String email = uid() + "@kbtu.kz";
        String studentToken = register(email);

        // Student calling admin endpoint → 403
        mvc.perform(get("/api/admin/reports/summary")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        // Admin gets the summary
        mvc.perform(get("/api/admin/reports/summary")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingsByStatus").exists());

        // Find the student's id
        long userId = om.readTree(
                mvc.perform(get("/api/client/auth/me").header("Authorization", bearer(studentToken)))
                        .andReturn().getResponse().getContentAsString()
        ).get("id").asLong();

        // Admin deactivates the user
        mvc.perform(patch("/api/admin/users/" + userId + "/active").contentType(JSON)
                        .header("Authorization", bearer(admin))
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // Login is now rejected
        mvc.perform(post("/api/client/auth/login").contentType(JSON)
                        .content(om.writeValueAsString(Map.of("email", email, "password", "Password123"))))
                .andExpect(status().isUnauthorized());
    }

    // ═══════════════════════════════════════════════════════════
    //  TEST 20 – Admin: create category, duplicate name returns 409,
    //             new category appears in public /categories list
    // ═══════════════════════════════════════════════════════════
    @Test
    void test20_adminCreateCategoryAndDuplicateIsConflict() throws Exception {
        String admin = adminToken();
        String name = "Cat" + uid();

        // Create a new category
        mvc.perform(post("/api/admin/categories").contentType(JSON)
                        .header("Authorization", bearer(admin))
                        .content("{\"name\":\"" + name + "\",\"description\":\"test category\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.active").value(true));

        // Duplicate name → 409
        mvc.perform(post("/api/admin/categories").contentType(JSON)
                        .header("Authorization", bearer(admin))
                        .content("{\"name\":\"" + name.toUpperCase() + "\"}"))
                .andExpect(status().isConflict());

        // Visible in public category list
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='" + name + "')].id").isNotEmpty());
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    /** Registers a user at /api/client/auth/register and returns the JWT. */
    private String register(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/client/auth/register").contentType(JSON)
                        .content(om.writeValueAsString(Map.of(
                                "email", email, "password", "Password123",
                                "name", "Test " + email.substring(0, 4)))))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    /** Logs in an existing user and returns the JWT (used for the pre-seeded admin). */
    private String login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/client/auth/login").contentType(JSON)
                        .content(om.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    /** Returns the JWT for the pre-seeded admin account (from application-test.yml). */
    private String adminToken() throws Exception {
        return login("admin@kbtu.kz", "Admin12345!");
    }

    /**
     * Creates a listing via /api/client/listings and returns its id.
     * @param token   seller JWT
     * @param title   listing title (must be ≥ 3 chars)
     * @param price   price in KZT (must be ≥ 1)
     * @param category category slug (e.g. "textbooks", "electronics", "other", "dorm_home")
     */
    private String createListing(String token, String title, int price, String category) throws Exception {
        MvcResult r = mvc.perform(post("/api/client/listings").contentType(JSON)
                        .header("Authorization", bearer(token))
                        .content(om.writeValueAsString(Map.of(
                                "title", title,
                                "price", price,
                                "category", category,
                                "condition", "good"))))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    /** Wraps a raw JWT in the Authorization header value expected by the API. */
    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Short random suffix used to keep test data unique across parallel runs. */
    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
