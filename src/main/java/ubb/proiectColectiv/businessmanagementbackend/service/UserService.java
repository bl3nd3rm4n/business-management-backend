package ubb.proiectColectiv.businessmanagementbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.var;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;
import ubb.proiectColectiv.businessmanagementbackend.model.FullUserSpecification;
import ubb.proiectColectiv.businessmanagementbackend.model.LoginResponseValue;
import ubb.proiectColectiv.businessmanagementbackend.model.ProjectExperienceEntry;
import ubb.proiectColectiv.businessmanagementbackend.model.TokenTransport;
import ubb.proiectColectiv.businessmanagementbackend.model.User;
import ubb.proiectColectiv.businessmanagementbackend.utils.FirebaseUtils;

import java.util.*;
import java.util.stream.Collectors;


@Service
public class UserService {

    private ObjectMapper mapper = new ObjectMapper();
    private HashMap<String, Long> lastLoginAttempts = new HashMap<>();

    // TODO: 11-Dec-19 documentation
    public TokenTransport login(String email, String password) {
        if (lastLoginAttempts.get(email) != null && System.currentTimeMillis() <= lastLoginAttempts.get(email) + 10 * 1000) //check if 10 seconds have passed since last login attempt
            return new TokenTransport(null, LoginResponseValue.SPAM);

        String hashedEmail = String.valueOf(Objects.hash(email));
        Object userInDatabase = FirebaseUtils.getUpstreamData(Arrays.asList("User", hashedEmail)); //get user from Firebase as HashMap
        if (userInDatabase != null) {
            User user = mapper.convertValue(userInDatabase, User.class); //convert HashMap to User POJO
            if (password.equals(user.getPassword())) {

                if (user.getBlockedStatus().equals(true)) {
                    lastLoginAttempts.put(email, System.currentTimeMillis()); //set time of last login attempt for current user
                    return new TokenTransport(null, LoginResponseValue.BLOCKED);
                }

                if (user.getFailedLoginCounter() != 0) {
                    FirebaseUtils.setValue(Arrays.asList("User", hashedEmail, "failedLoginCounter"), 0); //reset counter to 0 cause user entered correct credentials
                }

                if (user.getApprovedStatus().equals(true)) {
                    String token = RandomStringUtils.randomAlphanumeric(15);    //generate token for this user
                    if (TokenService.containsEmail(email)) {
                        TokenService.getTokens().get(email).add(token);
                    } else {
                        TokenService.getTokens().put(email, new ArrayList<>(Collections.singletonList(token)));
                    }
                    return new TokenTransport(token, LoginResponseValue.SUCCESSFUL);
                }
                return new TokenTransport(null, LoginResponseValue.UNAPPROVED);
            }

            lastLoginAttempts.put(email, System.currentTimeMillis()); //set time of last login attempt for current user
            FirebaseUtils.setValue(Arrays.asList("User", hashedEmail, "failedLoginCounter"), user.getFailedLoginCounter() + 1);
            if (user.getFailedLoginCounter() == 2) { //this counter is the one before the incrementation
                FirebaseUtils.setValue(Arrays.asList("User", hashedEmail, "blockedStatus"), Boolean.TRUE);
                return new TokenTransport(null, LoginResponseValue.BLOCKED);
            }
            return new TokenTransport(null, LoginResponseValue.WRONG_PASSWORD);
        }
        return new TokenTransport(null, LoginResponseValue.INEXISTENT);
    }

    // TODO: 11-Dec-19 documentation
    public String register(String email, String password) {
        Object userInDataBase = FirebaseUtils.getUpstreamData(Arrays.asList("User", String.valueOf(Objects.hash(email)), "password"));

        if (userInDataBase != null)
            return "EXISTS";

        User user = new User();
        user.setApprovedStatus(false);
        user.setBlockedStatus(false);
        user.setEmail(email);
        user.setFailedLoginCounter(0);
        user.setPassword(password);
        FirebaseUtils.setValue(Arrays.asList("User", String.valueOf(Objects.hash(email))), user);
        return "REGISTERED";
    }

    // TODO: 11-Dec-19 documentation
    public String logout(String email, String token) {
        if (TokenService.getTokens().get(email).remove(token))
            return "LOGGED OUT";
        else
            return "NOT LOGGED";
    }

    // TODO: 11-Dec-19 documentation
    public Object getPersonalData(String token, String email) {
        if (TokenService.containsToken(email, token)) {
            return FirebaseUtils.getUpstreamData(Arrays.asList("User", String.valueOf(Objects.hash(email))));
        }
        return "INVALID TOKEN";
    }

    /**
     * Retrieves the project experience entries which match the specified email.
     *
     * @param email The email to be matched by the project experience entries.
     *              Identifies the owner of the requested entries.
     * @return Collection of project experience entries which belong to the user identified by the provided email.
     */
    public List<ProjectExperienceEntry> getAllProjectExperienceEntriesForUserWithEmail(String email) {
        var hashedEmail = Objects.hash(email);
        return getProjectExperienceEntriesForEmailHash(hashedEmail);
    }

    // TODO: 11-Dec-19 documentation
    private List<ProjectExperienceEntry> getProjectExperienceEntriesForEmailHash(int hashedEmail) {
        var entries = new ArrayList<ProjectExperienceEntry>();
        var projectExperiences = FirebaseUtils.getCollectionAsUpstreamData(Arrays.asList("ProjectExperience"), HashMap.class);
        projectExperiences = projectExperiences.stream().filter(pe -> (Integer) pe.get("userId") == hashedEmail).collect(Collectors.toList());
        for (var projectExperienceMap : projectExperiences) {
            var consultingLevelId = (Integer) projectExperienceMap.get("consultingLevelId");
            var projectId = (Integer) projectExperienceMap.get("projectId");

            var consultingLevel = FirebaseUtils.getSingleAsUpstream(Arrays.asList("ConsultingLevel", consultingLevelId.toString()), String.class);
            var project = FirebaseUtils.getSingleAsUpstream(Arrays.asList("Project", projectId.toString()), HashMap.class);

            var clientId = (Integer) project.get("clientId");
            var industryId = (Integer) project.get("industryId");

            var client = FirebaseUtils.getSingleAsUpstream(Arrays.asList("Client", clientId.toString()), HashMap.class);
            var industry = FirebaseUtils.getSingleAsUpstream(Arrays.asList("Industry", industryId.toString()), String.class);

            var projectExperienceEntry = buildProjectExprienceEntry(projectExperienceMap, project, client, industry, consultingLevel);
            entries.add(projectExperienceEntry);
        }
        return entries;
    }

    // TODO: 11-Dec-19 documentation
    private ProjectExperienceEntry buildProjectExprienceEntry(Map<String, Object> entryFirstClassProperties, Map<String, Object> project, Map<String, Object> client, String industry, String consultingLevel) {
        return ProjectExperienceEntry.builder()
                .startDate((Integer) entryFirstClassProperties.get("startDate"))
                .endDate((Integer) entryFirstClassProperties.get("endDate"))
                .consultingLevel(consultingLevel)
                .description((String) entryFirstClassProperties.get("description"))
                .projectStartDate((Integer) project.get("startDate"))
                .projectEndDate((Integer) project.get("endDate"))
                .projectName((String) project.get("name"))
                .industry(industry)
                .clientAddress((String) client.get("address"))
                .clientName((String) client.get("name"))
                .build();
    }

    public void registerPendingChangeForUserWitHEmail(FullUserSpecification fullUserSpecification, String userEmail) {
        List path = Arrays.asList("User", String.valueOf(Objects.hash(userEmail)), "edits");
        FirebaseUtils.setValue(path, fullUserSpecification);
    }
}
