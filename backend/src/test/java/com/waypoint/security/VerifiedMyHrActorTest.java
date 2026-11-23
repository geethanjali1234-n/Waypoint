package com.waypoint.security;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.ServletRequestDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.cfg.ConstructorDetector;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.json.JsonMapper;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Every way request data could reach the constructor - reflection, Spring MVC constructor binding
 * and Jackson - must fail, leaving MyHrActorResolver as the only issuer.
 */
class VerifiedMyHrActorTest {

    private static final String SPOOFED_JSON =
            "{\"userId\":\"admin-9\",\"organizationId\":\"org-evil\",\"role\":\"COMPANY_ADMIN\",\"issuer\":{}}";

    // --- Issuing --------------------------------------------------------------------------------

    @Test
    void issuedActorCarriesTheVerifiedValues() {
        VerifiedMyHrActor actor = VerifiedMyHrActor.issue("hr-1", "org-1", UserRole.HR);

        assertThat(actor.userId()).isEqualTo("hr-1");
        assertThat(actor.organizationId()).isEqualTo("org-1");
        assertThat(actor.role()).isEqualTo(UserRole.HR);
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL,org-1,HR", "'  ',org-1,HR", "hr-1,NULL,HR", "hr-1,'',HR", "hr-1,org-1,NULL"},
            nullValues = "NULL")
    void issuingRequiresAUserAnOrganizationAndARole(String userId, String organizationId, UserRole role) {
        assertThatThrownBy(() -> VerifiedMyHrActor.issue(userId, organizationId, role))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void onlyHrAndCompanyAdminCanManageKnowledge(UserRole role) {
        VerifiedMyHrActor actor = VerifiedMyHrActor.issue("user-1", "org-1", role);

        assertThat(actor.canManageKnowledge()).isEqualTo(role == UserRole.HR || role == UserRole.COMPANY_ADMIN);
        assertThat(VerifiedMyHrActor.KNOWLEDGE_MANAGER_ROLES).containsExactlyInAnyOrder(UserRole.HR, UserRole.COMPANY_ADMIN);
    }

    @Test
    void hasAnyRoleChecksTheVerifiedRoleOnly() {
        VerifiedMyHrActor actor = VerifiedMyHrActor.issue("hr-1", "org-1", UserRole.HR);

        assertThat(actor.hasAnyRole(Set.of(UserRole.HR, UserRole.EMPLOYEE))).isTrue();
        assertThat(actor.hasAnyRole(Set.of(UserRole.COMPANY_ADMIN))).isFalse();
        assertThat(actor.hasAnyRole(Set.of())).isFalse();
        assertThat(actor.hasAnyRole(null)).isFalse();
    }

    @Test
    void toStringShowsTheRoleButNoIds() {
        String text = VerifiedMyHrActor.issue("hr-1", "org-1", UserRole.HR).toString();

        assertThat(text).isEqualTo("VerifiedMyHrActor[role=HR]").doesNotContain("hr-1").doesNotContain("org-1");
    }

    // --- Shape ----------------------------------------------------------------------------------

    @Test
    void isFinalImmutableAndNotSerializable() {
        assertThat(Modifier.isFinal(VerifiedMyHrActor.class.getModifiers())).isTrue();
        assertThat(Serializable.class.isAssignableFrom(VerifiedMyHrActor.class)).isFalse();
        for (Field field : VerifiedMyHrActor.class.getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue();
            if (!Modifier.isStatic(field.getModifiers())) {
                assertThat(Modifier.isPrivate(field.getModifiers())).as(field.getName()).isTrue();
            }
        }
        assertThatThrownBy(() -> VerifiedMyHrActor.KNOWLEDGE_MANAGER_ROLES.add(UserRole.EMPLOYEE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(Arrays.stream(VerifiedMyHrActor.class.getDeclaredMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("set"));
    }

    @Test
    void canOnlyBeCreatedThroughThePackagePrivateFactory() throws NoSuchMethodException {
        assertThat(VerifiedMyHrActor.class.getConstructors()).isEmpty();
        assertThat(VerifiedMyHrActor.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());

        int factory = VerifiedMyHrActor.class
                .getDeclaredMethod("issue", String.class, String.class, UserRole.class).getModifiers();
        assertThat(Modifier.isStatic(factory)).isTrue();
        assertThat(Modifier.isPublic(factory) || Modifier.isProtected(factory) || Modifier.isPrivate(factory)).isFalse();
        assertThat(Arrays.stream(VerifiedMyHrActor.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == VerifiedMyHrActor.class)
                .map(Method::getName))
                .containsExactly("issue");
    }

    @Test
    void reflectiveConstructionWithoutTheIssuerKeyFails() throws Exception {
        Constructor<?> constructor = VerifiedMyHrActor.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);

        for (Object forgedKey : Arrays.asList(new Object(), null, "issuer")) {
            assertThatThrownBy(() -> constructor.newInstance(forgedKey, "admin-9", "org-evil", UserRole.COMPANY_ADMIN))
                    .isInstanceOf(InvocationTargetException.class)
                    .cause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MyHrActorResolver");
        }
    }

    // --- Spring MVC request binding -------------------------------------------------------------

    @Test
    void constructorBindingFromRequestParametersCannotCreateAnActor() {
        // What @ModelAttribute does for an unannotated parameter: construct the type from request values.
        ServletRequestDataBinder binder = new ServletRequestDataBinder(null);
        binder.setTargetType(ResolvableType.forClass(VerifiedMyHrActor.class));

        Throwable failure = catchThrowable(() -> binder.construct(spoofedRequest()));

        assertThat(binder.getTarget()).isNull();
        // Binding reached the constructor with the attacker's values; the issuer check stopped it.
        assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MyHrActorResolver");
    }

    @Test
    void unannotatedHandlerParameterIsNeverPopulatedFromQueryParameters() {
        ProbeController probe = new ProbeController();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(probe).build();

        Throwable failure = catchThrowable(() -> mvc.perform(get("/probe/actor")
                .param("userId", "admin-9")
                .param("organizationId", "org-evil")
                .param("role", "COMPANY_ADMIN")
                .param("issuer", "x")));

        assertThat(probe.received).isNull();
        assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MyHrActorResolver");
    }

    @Test
    void requestBodyIsNeverDeserializedIntoAnActor() {
        ProbeController probe = new ProbeController();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(probe).build();

        Throwable failure = catchThrowable(() -> mvc.perform(
                post("/probe/actor").contentType(MediaType.APPLICATION_JSON).content(SPOOFED_JSON)));

        assertThat(probe.received).isNull();
        // Jackson finds no way to create the type at all, so the request fails before the handler.
        assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("no Creators");
    }

    @Test
    void jacksonCannotDeserializeAnActorEvenWithPermissiveSettings() {
        JsonMapper defaults = JsonMapper.builder().build();
        JsonMapper permissive = JsonMapper.builder()
                .changeDefaultVisibility(checker -> checker.withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY))
                .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
                .build();

        for (JsonMapper mapper : List.of(defaults, permissive)) {
            assertThatThrownBy(() -> mapper.readValue(SPOOFED_JSON, VerifiedMyHrActor.class))
                    .isInstanceOf(JacksonException.class);
        }
    }

    @Test
    void noApplicationControllerTakesAnActorAsAHandlerParameter() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        int controllers = 0;
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.waypoint")) {
            String className = candidate.getBeanClassName();
            if (className == null || className.startsWith(VerifiedMyHrActorTest.class.getName())) {
                continue;
            }
            controllers++;
            for (Method method : Class.forName(className).getDeclaredMethods()) {
                assertThat(method.getParameterTypes())
                        .as(className + "." + method.getName() + " must call MyHrActorResolver instead")
                        .doesNotContain(VerifiedMyHrActor.class);
            }
        }
        assertThat(controllers).as("controllers scanned").isGreaterThan(10);
    }

    // --- Helpers --------------------------------------------------------------------------------

    private static MockHttpServletRequest spoofedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe/actor");
        request.addParameter("userId", "admin-9");
        request.addParameter("organizationId", "org-evil");
        request.addParameter("role", "COMPANY_ADMIN");
        request.addParameter("issuer", "x");
        return request;
    }

    /** Test-only handler that declares the actor the way a careless controller might. */
    @RestController
    static class ProbeController {
        VerifiedMyHrActor received;

        @GetMapping("/probe/actor")
        String fromQuery(VerifiedMyHrActor actor) {
            received = actor;
            return "bound";
        }

        @PostMapping("/probe/actor")
        String fromBody(@RequestBody VerifiedMyHrActor actor) {
            received = actor;
            return "bound";
        }
    }
}
