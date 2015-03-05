package femr.ui.controllers;

import com.google.inject.Inject;
import femr.business.services.core.*;
import femr.common.dtos.CurrentUser;
import femr.common.dtos.ServiceResponse;
import femr.common.models.*;
import femr.data.models.mysql.Roles;
import femr.ui.controllers.helpers.FieldHelper;
import femr.ui.helpers.security.AllowedRoles;
import femr.ui.helpers.security.FEMRAuthenticated;
import femr.ui.models.medical.*;
import femr.ui.views.html.medical.index;
import femr.ui.views.html.medical.edit;
import femr.ui.views.html.medical.newVitals;
import femr.ui.views.html.medical.listVitals;
import femr.util.DataStructure.Mapping.TabFieldMultiMap;
import femr.util.DataStructure.Mapping.VitalMultiMap;
import femr.util.stringhelpers.StringUtils;
import play.data.Form;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;

import java.util.*;

@Security.Authenticated(FEMRAuthenticated.class)
@AllowedRoles({Roles.PHYSICIAN, Roles.PHARMACIST, Roles.NURSE})
public class MedicalController extends Controller {

    private final Form<EditViewModelPost> createViewModelPostForm = Form.form(EditViewModelPost.class);
    private final Form<UpdateVitalsModel> updateVitalsModelForm = Form.form(UpdateVitalsModel.class);
    private final ITabService tabService;
    private final IEncounterService encounterService;
    private final IMedicationService medicationService;
    private final IPhotoService photoService;
    private final ISessionService sessionService;
    private final ISearchService searchService;
    private final IVitalService vitalService;
    private final FieldHelper fieldHelper;

    @Inject
    public MedicalController(ITabService tabService,
                             IEncounterService encounterService,
                             IMedicationService medicationService,
                             IPhotoService photoService,
                             ISessionService sessionService,
                             ISearchService searchService,
                             IVitalService vitalService) {
        this.tabService = tabService;
        this.encounterService = encounterService;
        this.sessionService = sessionService;
        this.searchService = searchService;
        this.medicationService = medicationService;
        this.photoService = photoService;
        this.vitalService = vitalService;
        this.fieldHelper = new FieldHelper();
    }

    public Result indexGet() {
        CurrentUser currentUserSession = sessionService.retrieveCurrentUserSession();

        return ok(index.render(currentUserSession, null, 0));
    }

    public Result indexPost() {
        CurrentUser currentUserSession = sessionService.retrieveCurrentUserSession();

        String queryString_id = request().body().asFormUrlEncoded().get("id")[0];
        ServiceResponse<Integer> idQueryStringResponse = searchService.parseIdFromQueryString(queryString_id);
        if (idQueryStringResponse.hasErrors()) {

            return ok(index.render(currentUserSession, idQueryStringResponse.getErrors().get(""), 0));
        }
        Integer patientId = idQueryStringResponse.getResponseObject();

        //get the patient's encounter
        ServiceResponse<PatientEncounterItem> patientEncounterItemServiceResponse = searchService.retrieveRecentPatientEncounterItemByPatientId(patientId);
        if (patientEncounterItemServiceResponse.hasErrors()) {

            return ok(index.render(currentUserSession, patientEncounterItemServiceResponse.getErrors().get(""), 0));
        }
        PatientEncounterItem patientEncounterItem = patientEncounterItemServiceResponse.getResponseObject();

        //check for encounter closed
        if (patientEncounterItem.getIsClosed()) {

            return ok(index.render(currentUserSession, "That patient's encounter has been closed.", 0));
        }

        //check if the doc has already seen the patient today
        ServiceResponse<UserItem> userItemServiceResponse = encounterService.retrievePhysicianThatCheckedInPatientToMedical(patientEncounterItem.getId());
        if (userItemServiceResponse.hasErrors()) {

            throw new RuntimeException();
        } else {

            if (userItemServiceResponse.getResponseObject() != null) {

                return ok(index.render(currentUserSession, "That patient has already been seen today. Would you like to edit their encounter?", patientId));
            }
        }

        return redirect(routes.MedicalController.editGet(patientId));
    }

    public Result editGet(int patientId) {

        CurrentUser currentUserSession = sessionService.retrieveCurrentUserSession();

        EditViewModelGet viewModelGet = new EditViewModelGet();

        //Get Patient Encounter
        PatientEncounterItem patientEncounter;
        ServiceResponse<PatientEncounterItem> patientEncounterItemServiceResponse = searchService.retrieveRecentPatientEncounterItemByPatientId(patientId);
        if (patientEncounterItemServiceResponse.hasErrors()) {

            throw new RuntimeException();
        }
        patientEncounter = patientEncounterItemServiceResponse.getResponseObject();
        viewModelGet.setPatientEncounterItem(patientEncounter);

        //verify encounter is still open
        if (patientEncounter.getIsClosed()) {

            return ok(index.render(currentUserSession, "That patient's encounter has been closed.", 0));
        }

        //get patient
        ServiceResponse<PatientItem> patientItemServiceResponse = searchService.retrievePatientItemByPatientId(patientId);
        if (patientItemServiceResponse.hasErrors()) {

            throw new RuntimeException();
        }
        viewModelGet.setPatientItem(patientItemServiceResponse.getResponseObject());

        //get prescriptions
        ServiceResponse<List<PrescriptionItem>> prescriptionItemServiceResponse = searchService.retrieveUnreplacedPrescriptionItems(patientEncounter.getId());
        if (prescriptionItemServiceResponse.hasErrors()) {

            throw new RuntimeException();
        }
        viewModelGet.setPrescriptionItems(prescriptionItemServiceResponse.getResponseObject());

        //get problems
        ServiceResponse<List<ProblemItem>> problemItemServiceResponse = encounterService.retrieveProblemItems(patientEncounter.getId());
        if (problemItemServiceResponse.hasErrors()) {

            throw new RuntimeException();
        }
        viewModelGet.setProblemItems(problemItemServiceResponse.getResponseObject());

        //get vitals
        ServiceResponse<VitalMultiMap> vitalMapResponse = vitalService.retrieveVitalMultiMap(patientEncounter.getId());
        if (vitalMapResponse.hasErrors()) {

            throw new RuntimeException();
        }

        //get all fields and their values
        ServiceResponse<TabFieldMultiMap> tabFieldMultiMapResponse = tabService.retrieveTabFieldMultiMap(patientEncounter.getId());
        if (tabFieldMultiMapResponse.hasErrors()) {

            throw new RuntimeException();
        }
        TabFieldMultiMap tabFieldMultiMap = tabFieldMultiMapResponse.getResponseObject();
        ServiceResponse<List<TabItem>> tabItemServiceResponse = tabService.retrieveAvailableTabs(false);
        if (tabItemServiceResponse.hasErrors()) {

            throw new RuntimeException();
        }
        List<TabItem> tabItems = tabItemServiceResponse.getResponseObject();
        //match the fields to their respective tabs
        for (TabItem tabItem : tabItems) {

            switch (tabItem.getName().toLowerCase()) {
                case "hpi":
                    tabItem.setFields(FieldHelper.structureHPIFieldsForView(tabFieldMultiMap));
                    break;
                case "pmh":
                    tabItem.setFields(FieldHelper.structurePMHFieldsForView(tabFieldMultiMap));
                    break;
                case "treatment":
                    tabItem.setFields(FieldHelper.structureTreatmentFieldsForView(tabFieldMultiMap));
                    break;
                default:
                    tabItem.setFields(fieldHelper.structureDynamicFieldsForView(tabFieldMultiMap));
                    break;
            }
        }
        tabItems = FieldHelper.applyIndicesToFieldsForView(tabItems);
        viewModelGet.setTabItems(tabItems);
        viewModelGet.setChiefComplaints(tabFieldMultiMap.getChiefComplaintList());

        ServiceResponse<List<PhotoItem>> photoListResponse = photoService.retrieveEncounterPhotos(patientEncounter.getId());
        if (photoListResponse.hasErrors()) {

            throw new RuntimeException();
        } else {

            viewModelGet.setPhotos(photoListResponse.getResponseObject());
        }

        ServiceResponse<SettingItem> response = searchService.retrieveSystemSettings();
        viewModelGet.setSettings(response.getResponseObject());

        //Alaa Serhan - Purple Attempt
        VitalMultiMap vitalMultiMap = vitalMapResponse.getResponseObject();
        // Check if Metric is Set
        // If Metric, GET values from map, convert and put BACK Into MAP

        return ok(edit.render(currentUserSession, vitalMultiMap, viewModelGet));
    }

    public Result editPost(int patientId) {
        CurrentUser currentUserSession = sessionService.retrieveCurrentUserSession();

        //Alaa - I need to Add Stuff Here



        EditViewModelPost viewModelPost = createViewModelPostForm.bindFromRequest().get();

        //get current patient
        ServiceResponse<PatientItem> patientItemServiceResponse = searchService.retrievePatientItemByPatientId(patientId);
        if (patientItemServiceResponse.hasErrors()) {
            throw new RuntimeException();
        }
        PatientItem patientItem = patientItemServiceResponse.getResponseObject();

        //get current encounter
        ServiceResponse<PatientEncounterItem> patientEncounterServiceResponse = searchService.retrieveRecentPatientEncounterItemByPatientId(patientId);
        if (patientEncounterServiceResponse.hasErrors()) {
            throw new RuntimeException();
        }
        PatientEncounterItem patientEncounterItem = patientEncounterServiceResponse.getResponseObject();
        patientEncounterItem = encounterService.checkPatientInToMedical(patientEncounterItem.getId(), currentUserSession.getId()).getResponseObject();

        //get and save problems
        List<String> problemList = new ArrayList<>();
        for (ProblemItem pi : viewModelPost.getProblems()) {

            if (StringUtils.isNotNullOrWhiteSpace(pi.getName())) {

                problemList.add(pi.getName());
            }

        }
        if (problemList.size() > 0) {

            encounterService.createProblems(problemList, patientEncounterItem.getId(), currentUserSession.getId());
        }

        //get and save tab fields
        List<TabFieldItem> tabFieldItems = new ArrayList<>();
        //get non-custom tab fields other than problems
        for (TabFieldItem tfi : viewModelPost.getTabFieldItems()) {

            if (StringUtils.isNotNullOrWhiteSpace(tfi.getValue())) {
                tfi.setValue(tfi.getValue().trim());
                tabFieldItems.add(tfi);
            }
        }
        if (tabFieldItems.size() > 0) {

            ServiceResponse<List<TabFieldItem>> createPatientEncounterTabFieldsServiceResponse = encounterService.createPatientEncounterTabFields(tabFieldItems, patientEncounterItem.getId(), currentUserSession.getId());
            if (createPatientEncounterTabFieldsServiceResponse.hasErrors()) {

                throw new RuntimeException();
            }
        }



        /*
        //get custom tab fields
        Map<String, List<JCustomField>> customFieldInformation = new Gson().fromJson(viewModelPost.getCustomFieldJSON(), new TypeToken<Map<String, List<JCustomField>>>() {
        }.getType());
        for (Map.Entry<String, List<JCustomField>> entry : customFieldInformation.entrySet()) {
            for (JCustomField jcf : entry.getValue()) {
                if (StringUtils.isNotNullOrWhiteSpace(jcf.getValue()))
                    tabFieldMultiMap.put(jcf.getName(), date, "", jcf.getValue());
            }
        } */
        //save dat sheeeit, mayne
        //if (tabFieldsWithValue.size() > 0) {


        //create patient encounter photos
        photoService.createEncounterPhotos(request().body().asMultipartFormData().getFiles(), patientEncounterItem, viewModelPost);

        //create prescriptions
        List<String> prescriptions = new ArrayList<>();
        for (PrescriptionItem pi : viewModelPost.getPrescriptions()) {
            if (StringUtils.isNotNullOrWhiteSpace(pi.getName()))
                prescriptions.add(pi.getName());
        }
        if (prescriptions.size() > 0) {
            ServiceResponse<List<PrescriptionItem>> prescriptionResponse = medicationService.createPatientPrescriptions(prescriptions, currentUserSession.getId(), patientEncounterItem.getId(), false, false);
            if (prescriptionResponse.hasErrors()) {
                throw new RuntimeException();
            }
        }


        String message = "Patient information for " + patientItem.getFirstName() + " " + patientItem.getLastName() + " (id: " + patientItem.getId() + ") was saved successfully.";

        return ok(index.render(currentUserSession, message, 0));
    }

    public Result updateVitalsPost(int id) {

        CurrentUser currentUser = sessionService.retrieveCurrentUserSession();

        // Alaa Serhan
        EditViewModelGet viewModelGet = new EditViewModelGet();
        ServiceResponse<SettingItem> response = searchService.retrieveSystemSettings();
        viewModelGet.setSettings(response.getResponseObject());


        ServiceResponse<PatientEncounterItem> currentEncounterByPatientId = searchService.retrieveRecentPatientEncounterItemByPatientId(id);
        if (currentEncounterByPatientId.hasErrors()) {
            throw new RuntimeException();
        }
        //update date_of_medical_visit when a vital is updated
        encounterService.checkPatientInToMedical(currentEncounterByPatientId.getResponseObject().getId(), currentUser.getId());

        PatientEncounterItem patientEncounter = currentEncounterByPatientId.getResponseObject();

        UpdateVitalsModel updateVitalsModel = updateVitalsModelForm.bindFromRequest().get();

        Map<String, Float> patientEncounterVitals = getPatientEncounterVitals(updateVitalsModel);
        ServiceResponse<List<VitalItem>> patientEncounterVitalsServiceResponse =
                vitalService.createPatientEncounterVitals(patientEncounterVitals, currentUser.getId(), patientEncounter.getId());
        if (patientEncounterVitalsServiceResponse.hasErrors()) {
            throw new RuntimeException();
        }

        if( viewModelGet.getSettings().isMetric() ) {
            //Alaa Serhan
            Float temperature = patientEncounterVitals.get("temperature");
            Float celsius = (temperature - 32)/(1.800f);
            patientEncounterVitals.put("temperature", celsius); // puts it back into map

            Float feet = patientEncounterVitals.get("heightFeet");
            Float meters = feet/ (3.2808f);
            patientEncounterVitals.put("heightFeet", meters); // puts it back into map

            Float inches = patientEncounterVitals.get("heightInches");
            Float cm = inches/ (0.39370f);
            patientEncounterVitals.put("heightInches", cm); // puts it back into map

            Float lbs = patientEncounterVitals.get("weight");
            Float kgs = lbs/ (2.2046f);
            patientEncounterVitals.put("weight", kgs); // puts it back into map
        }

        return ok("true");
    }

    //partials
    public Result newVitalsGet() {

        // Alaa Serhan - Add View Model to Get the Settings to see if METRIC SYSTEM are set or not
        EditViewModelGet viewModelGet = new EditViewModelGet();
        ServiceResponse<SettingItem> response = searchService.getSystemSettings();
        viewModelGet.setSettings(response.getResponseObject());

        return ok(newVitals.render(viewModelGet));
    }

    public Result listVitalsGet(Integer id) {
        // Alaa Serhan
        EditViewModelGet viewModelGet = new EditViewModelGet();
        ServiceResponse<SettingItem> response = searchService.getSystemSettings();
        viewModelGet.setSettings(response.getResponseObject());

        ServiceResponse<PatientEncounterItem> patientEncounterServiceResponse = searchService.retrieveRecentPatientEncounterItemByPatientId(id);
        if (patientEncounterServiceResponse.hasErrors()) {
            throw new RuntimeException();
        }
        ServiceResponse<VitalMultiMap> vitalMultiMapServiceResponse = vitalService.retrieveVitalMultiMap(patientEncounterServiceResponse.getResponseObject().getId());
        if (vitalMultiMapServiceResponse.hasErrors()) {
            throw new RuntimeException();
        }

        //Alaa Serhan
        // Check if Metric is Set
        // If metric, Get Values from Map, Convert and Put Back Into Map
        VitalMultiMap vitalMap = vitalMultiMapServiceResponse.getResponseObject();
        if( viewModelGet.getSettings().isMetric() ) {
            // changed: dateIndex <= vitalMap.getDateList().size()
            // -- using this gives an out of bounds error
            // -- .size() is the size of an array at starting index 0
            for(int dateIndex = 0; dateIndex < vitalMap.getDateListChronological().size(); dateIndex++) {
                // You are only converting temperature here, need to do the same for the other vitals within this loop
                // Other than the OutOfBoundsException I was getting, this seems to be working fine
                String temp = vitalMap.get("temperature", vitalMap.getDate(dateIndex));
                Float tempC = Float.parseFloat(temp);
                // (°F - 32) x 5/9 = °C
                tempC = (tempC - 32) * 5/9;
                // 2015-03-03 16:59:14.0 date format
                vitalMap.put("temperature", vitalMap.getDate(dateIndex), tempC);

                String feetS = vitalMap.get("heightFeet", vitalMap.getDate(dateIndex));
                Float feet = Float.parseFloat(feetS);
                Float meters = feet/ (3.2808f);
                vitalMap.put("heightFeet", vitalMap.getDate(dateIndex),meters); // puts it back into map

                String inchesS = vitalMap.get("heightInches", vitalMap.getDate(dateIndex));
                Float inches = Float.parseFloat(inchesS);
                Float cm = inches/ (0.39370f);
                vitalMap.put("heightInches", vitalMap.getDate(dateIndex),cm); // puts it back into map

                String lb = vitalMap.get("heightInches", vitalMap.getDate(dateIndex));
                Float lbs = Float.parseFloat(lb);
                Float kgs = lbs/ (2.2046f);
                vitalMap.put("weight", vitalMap.getDate(dateIndex), kgs); // puts it back into map
            }
        }

        return ok(listVitals.render(vitalMap));
    }

    /**
     * Maps vitals from view model to a Map structure
     *
     * @param viewModel the view model with POST data
     * @return Mapped vital value to vital name
     */
    private Map<String, Float> getPatientEncounterVitals(UpdateVitalsModel viewModel) {
        EditViewModelGet viewModelGet = new EditViewModelGet();
        ServiceResponse<SettingItem> response = searchService.getSystemSettings();
        viewModelGet.setSettings(response.getResponseObject());

        Map<String, Float> newVitals = new HashMap<>();
        if (viewModel.getRespiratoryRate() != null) {
            newVitals.put("respiratoryRate", viewModel.getRespiratoryRate());
        }
        if (viewModel.getHeartRate() != null) {
            newVitals.put("heartRate", viewModel.getHeartRate());
        }

        //Alaa Serhan
        if (viewModel.getTemperature() != null) {
            Float temperature = viewModel.getTemperature();
            if(viewModelGet.getSettings().isMetric() ){

                // Value Entered in Celsius - Will be Returned Back As Metric to User, Converted to Fahrenheit when Saving
                temperature = temperature * 9/5 + 32;
            }

            newVitals.put("temperature", temperature);
        }
        if (viewModel.getOxygenSaturation() != null) {
            newVitals.put("oxygenSaturation", viewModel.getOxygenSaturation());
        }

        //Alaa Serhan
        if (viewModel.getHeightFeet() != null) {

            Float heightFeet = viewModel.getHeightFeet().floatValue();

            if(viewModelGet.getSettings().isMetric() ){

                //Value Entered in Meters - Will be Converted Back in Feet
                heightFeet = heightFeet * 3.2808f;
            }
            newVitals.put("heightFeet", heightFeet);
        }

        //Alaa Serhan
        if (viewModel.getHeightInches() != null) {

            Float heightInches = viewModel.getHeightInches().floatValue();

            if(viewModelGet.getSettings().isMetric() ){

                //Value Entered in Centimeters - Will be Converted Back in Inches
                heightInches = heightInches * (0.39370f);
            }
            newVitals.put("heightInches", heightInches);
        }

        //Alaa Serhan
        if (viewModel.getWeight() != null) {
            Float weight = viewModel.getWeight();

            if(viewModelGet.getSettings().isMetric() ){

                //Value Entered in Kilograms - Will be Converted back in Pounds
                weight = weight * (2.204f);
            }
            newVitals.put("weight", weight);
        }
        if (viewModel.getBloodPressureSystolic() != null) {
            newVitals.put("bloodPressureSystolic", viewModel.getBloodPressureSystolic());
        }
        if (viewModel.getBloodPressureDiastolic() != null) {
            newVitals.put("bloodPressureDiastolic", viewModel.getBloodPressureDiastolic());
        }
        if (viewModel.getGlucose() != null) {
            newVitals.put("glucose", viewModel.getGlucose());
        }
        return newVitals;
    }

    /*
    private List<TabFieldItem> mapHpiFieldItemsFromJSON(String JSON) {
        List<TabFieldItem> tabFieldItems = new ArrayList<>();
        Gson gson = new Gson();
        //get values from JSON, assign list of values to chief complaint
        Map<String, List<JCustomField>> hpiTabInformation = gson.fromJson(JSON, new TypeToken<Map<String, List<JCustomField>>>() {
        }.getType());

        for (Map.Entry<String, List<JCustomField>> entry : hpiTabInformation.entrySet()) {
            List<JCustomField> fields = entry.getValue();

            for (JCustomField jcf : fields) {
                TabFieldItem tabFieldItem = new TabFieldItem();
                tabFieldItem = jcf.getName());
                tabFieldItem.setChiefComplaint(entry.getKey().trim());
                tabFieldItem.setIsCustom(false);
                tabFieldItem.setValue(jcf.getValue());
                tabFieldItems.add(tabFieldItem);
            }
        }
        return tabFieldItems;
    }     */
}
