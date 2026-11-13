package com.mytv.launcher.ang

import android.content.Context
import java.time.LocalDate

/** One board-style question: a vignette, five choices, the answer and why. */
data class MedQuestion(
    val topic: String,
    val stem: String,
    val choices: List<String>,
    /** Index into [choices]. */
    val answer: Int,
    val explanation: String,
)

/**
 * Ang's Medical Question of the Day: board-style vignettes at the level of a
 * second-year resident (in-training exam / Step 3). Same question all day,
 * a new one at midnight, and the whole bank before any repeats.
 */
object MedQuestions {

    fun forDay(day: LocalDate): MedQuestion = all[Math.floorMod(day.toEpochDay(), all.size.toLong()).toInt()]

    val all: List<MedQuestion> = listOf(
        MedQuestion(
            "Cardiology",
            "A 68-year-old man has an inferior STEMI. BP is 82/50, JVP is elevated, and the lungs are clear. Right-sided leads show ST elevation in V4R. Before reperfusion, what is the best next step for his blood pressure?",
            listOf("Sublingual nitroglycerin", "IV furosemide", "IV normal saline bolus", "IV metoprolol", "IV morphine"),
            2,
            "Right ventricular infarction is preload-dependent: hypotension with clear lungs and high JVP responds to IV fluids. Nitrates, diuretics and morphine drop preload and can cause profound hypotension.",
        ),
        MedQuestion(
            "Endocrinology",
            "A 24-year-old woman in DKA has been on an insulin infusion for 6 hours. Glucose is now 230 mg/dL, but the anion gap is still 18 and bicarbonate is 14. Potassium is 4.1. What is the best next step?",
            listOf("Stop the infusion and start subcutaneous insulin", "Add dextrose to IV fluids and continue the insulin infusion", "Double the insulin infusion rate", "Give IV sodium bicarbonate", "Stop IV fluids"),
            1,
            "Insulin is titrated to close the anion gap, not to normalize glucose. Once glucose falls to about 200–250, add dextrose (e.g., D5 ½NS) and keep the infusion running until the gap closes.",
        ),
        MedQuestion(
            "Endocrinology",
            "A 31-year-old man presents in DKA. Glucose 540 mg/dL, pH 7.12, potassium 3.0 mEq/L. A 1 L saline bolus is running. What should happen before starting the insulin infusion?",
            listOf("Give IV bicarbonate", "Replete potassium until it is at least 3.3 mEq/L", "Check a serum phosphate", "Give a 10-unit insulin bolus", "Start subcutaneous glargine"),
            1,
            "Insulin drives potassium into cells. If K is below 3.3, hold insulin and replete potassium first to avoid life-threatening hypokalemia and arrhythmias.",
        ),
        MedQuestion(
            "Nephrology",
            "A 70-year-old man on dialysis missed two sessions. Potassium is 7.4 mEq/L and the ECG shows peaked T waves and a widened QRS. What is the first medication to give?",
            listOf("IV insulin with dextrose", "Nebulized albuterol", "Sodium zirconium cyclosilicate", "IV calcium gluconate", "IV furosemide"),
            3,
            "With ECG changes, IV calcium comes first: it stabilizes the cardiac membrane within minutes. Insulin/dextrose and albuterol shift potassium next; binders and dialysis remove it.",
        ),
        MedQuestion(
            "Pulmonology",
            "A 54-year-old woman has a CT-confirmed pulmonary embolism. After 2 L of fluids her BP is 78/50 and she has RV dilation on echo. She had no recent surgery, bleeding or stroke. What is the best next step?",
            listOf("Systemic thrombolysis with alteplase", "IVC filter placement", "Start apixaban", "Subcutaneous enoxaparin and observe", "Repeat CT in 24 hours"),
            0,
            "This is a high-risk (massive) PE: sustained hypotension. Without contraindications, give systemic thrombolysis. Catheter-directed therapy or embolectomy are options if lysis is contraindicated or fails.",
        ),
        MedQuestion(
            "Infectious Disease",
            "A healthy 29-year-old with no comorbidities and no recent antibiotics has community-acquired pneumonia and will be treated as an outpatient. Local pneumococcal macrolide resistance is 30%. What is the preferred regimen?",
            listOf("Azithromycin alone", "Levofloxacin", "High-dose amoxicillin", "Ceftriaxone plus azithromycin", "Vancomycin plus piperacillin-tazobactam"),
            2,
            "Per ATS/IDSA, healthy outpatients get amoxicillin 1 g three times daily (or doxycycline). Macrolide monotherapy only if local resistance is under 25%; fluoroquinolones are reserved for patients with comorbidities.",
        ),
        MedQuestion(
            "Hematology",
            "On day 6 of IV heparin, a patient's platelets fall from 240,000 to 95,000 and a new left femoral DVT is found. What is the best next step?",
            listOf("Switch to enoxaparin", "Transfuse platelets", "Stop heparin and start argatroban", "Stop heparin and start warfarin", "Continue heparin and recheck platelets"),
            2,
            "Suspected HIT with thrombosis: stop all heparin (including LMWH) and start a non-heparin anticoagulant such as argatroban. Warfarin must not be started until platelets recover (risk of venous limb gangrene).",
        ),
        MedQuestion(
            "Gastroenterology",
            "A man with cirrhosis presents with hematemesis from suspected variceal bleeding. He is receiving IV fluids, octreotide and is scheduled for EGD. Which additional treatment has been shown to reduce mortality?",
            listOf("IV pantoprazole infusion", "IV ceftriaxone", "Transfusion to hemoglobin above 10", "Fresh frozen plasma for INR 1.8", "Oral propranolol now"),
            1,
            "Short-term antibiotic prophylaxis (ceftriaxone) in cirrhotics with GI bleeding reduces infections, rebleeding and mortality. Use a restrictive transfusion target (about 7 g/dL); over-transfusion raises portal pressure.",
        ),
        MedQuestion(
            "Neurology",
            "A 66-year-old woman has acute left hemiparesis that began 90 minutes ago. CT shows no hemorrhage and she is eligible for thrombolysis, but her BP is 196/112. What is the best next step?",
            listOf("Give alteplase now", "IV labetalol to lower BP below 185/110, then thrombolysis", "Lower BP to below 140/90 first", "Give aspirin 325 mg", "Wait 24 hours and recheck BP"),
            1,
            "BP must be below 185/110 before thrombolysis and kept below 180/105 for 24 hours after. Use IV labetalol or nicardipine; aspirin is held for 24 hours after alteplase.",
        ),
        MedQuestion(
            "Cardiology",
            "A 72-year-old woman with hypertension has new non-valvular atrial fibrillation. She has normal renal function and no bleeding history. What is the best long-term stroke prevention?",
            listOf("No therapy", "Aspirin alone", "Aspirin plus clopidogrel", "Apixaban", "Warfarin with INR goal 3–4"),
            3,
            "CHA₂DS₂-VASc = 3 (age 65–74, female, hypertension), so anticoagulate. DOACs such as apixaban are preferred over warfarin in non-valvular AF; aspirin is not adequate stroke prevention.",
        ),
        MedQuestion(
            "Endocrinology",
            "A 38-year-old woman with Graves disease presents with fever 40°C, AF with RVR, agitation and diarrhea. You plan to give propranolol, hydrocortisone and potassium iodide. What must be given at least 1 hour before the iodine?",
            listOf("Propylthiouracil", "Levothyroxine", "Radioactive iodine", "Acetaminophen", "Cholestyramine"),
            0,
            "In thyroid storm, block synthesis with a thionamide (PTU preferred, it also blocks peripheral T4→T3) before giving iodine; otherwise the iodine becomes substrate for new hormone (Jod-Basedow).",
        ),
        MedQuestion(
            "Nephrology",
            "A 61-year-old man with small-cell lung cancer has a generalized seizure. Serum sodium is 114 mEq/L, he is euvolemic, and urine osmolality is 520 mOsm/kg. What is the best immediate treatment?",
            listOf("Fluid restriction to 1 L/day", "Normal saline infusion", "100 mL bolus of 3% hypertonic saline", "Oral tolvaptan", "Demeclocycline"),
            2,
            "Severe symptomatic hyponatremia (seizure) needs a 3% saline bolus (100–150 mL, repeatable) to raise Na 4–6 mEq/L quickly. Then limit correction to under 8–10 mEq/L in 24 h to avoid osmotic demyelination.",
        ),
        MedQuestion(
            "Infectious Disease",
            "A 63-year-old man has fever, neck stiffness and confusion. CSF shows 1,800 WBC (90% neutrophils), low glucose and high protein. Along with dexamethasone, what is the best empiric therapy?",
            listOf("Ceftriaxone alone", "Vancomycin plus ceftriaxone", "Vancomycin, ceftriaxone and ampicillin", "Acyclovir alone", "Meropenem alone"),
            2,
            "Adults over 50 need Listeria coverage: add ampicillin to vancomycin plus a third-generation cephalosporin. Give dexamethasone with or just before the first antibiotic dose.",
        ),
        MedQuestion(
            "Rheumatology",
            "A 70-year-old man on warfarin with CKD stage 4 (eGFR 22) has an acute gout flare of the right knee. What is the best treatment?",
            listOf("Naproxen", "Full-dose colchicine", "Oral prednisone", "Start allopurinol 300 mg now", "Indomethacin"),
            2,
            "NSAIDs are a poor choice with CKD and warfarin, and colchicine is risky in severe CKD. Glucocorticoids (oral or intra-articular) are preferred. Urate-lowering therapy is not started at full dose during a flare.",
        ),
        MedQuestion(
            "Cardiology",
            "A 19-year-old athlete has a systolic murmur at the left sternal border. Which maneuver increases the murmur if it's due to hypertrophic obstructive cardiomyopathy?",
            listOf("Squatting", "Sustained handgrip", "Passive leg raise", "Valsalva strain phase", "Lying supine"),
            3,
            "HOCM murmurs get louder when LV volume falls (Valsalva, standing) and softer when preload or afterload rises (squatting, handgrip, leg raise). Aortic stenosis does the opposite with Valsalva.",
        ),
        MedQuestion(
            "Pulmonology",
            "A 67-year-old with COPD has an exacerbation. ABG: pH 7.28, PaCO₂ 68, PaO₂ 58 on 2 L. He is alert, protecting his airway and following commands. What is the best next step?",
            listOf("Intubation", "Noninvasive positive-pressure ventilation (BiPAP)", "Increase oxygen to 6 L nasal cannula", "High-flow nasal cannula at 100% FiO₂", "IV bicarbonate"),
            1,
            "Hypercapnic respiratory acidosis (pH 7.25–7.35) in COPD: NIPPV reduces intubation and mortality. Target SpO₂ 88–92%; excess oxygen worsens hypercapnia.",
        ),
        MedQuestion(
            "Hematology",
            "A 34-year-old woman has confusion, fever, platelets 18,000, hemoglobin 8.1 with schistocytes, elevated LDH and normal coagulation studies. What is the best next step?",
            listOf("Platelet transfusion", "Plasma exchange", "IVIG", "Splenectomy", "Observation"),
            1,
            "Suspected TTP (low ADAMTS13): start plasma exchange urgently, with steroids (± caplacizumab), without waiting for the ADAMTS13 result. Platelet transfusion can worsen thrombosis.",
        ),
        MedQuestion(
            "Oncology",
            "A patient with Burkitt lymphoma is starting chemotherapy and is at high risk for tumor lysis syndrome. You plan to give rasburicase. Which test should be checked first?",
            listOf("TPMT activity", "G6PD level", "HLA-B*5801", "Serum ferritin", "Haptoglobin"),
            1,
            "Rasburicase produces hydrogen peroxide and can cause severe hemolysis and methemoglobinemia in G6PD deficiency. (HLA-B*5801 is for allopurinol hypersensitivity.)",
        ),
        MedQuestion(
            "Cardiology",
            "A 30-year-old has sharp chest pain that improves when leaning forward, a friction rub, and diffuse ST elevation with PR depression. Troponin and echo are normal. What is the best treatment?",
            listOf("Prednisone alone", "Ibuprofen plus colchicine", "Heparin infusion", "Pericardiocentesis", "Cardiac catheterization"),
            1,
            "Acute idiopathic pericarditis: high-dose NSAID (or aspirin) plus colchicine for 3 months, which cuts recurrence. Steroids raise recurrence risk and are second-line.",
        ),
        MedQuestion(
            "Cardiology",
            "A woman with metastatic breast cancer has dyspnea, hypotension, JVD, muffled heart sounds and a 15 mmHg drop in systolic BP on inspiration. Which ECG finding is most characteristic?",
            listOf("Electrical alternans", "S1Q3T3", "Delta waves", "Osborn (J) waves", "Prolonged QT"),
            0,
            "Cardiac tamponade: Beck's triad plus pulsus paradoxus; low voltage and electrical alternans (the heart swinging in fluid) on ECG. Treat with urgent pericardiocentesis.",
        ),
        MedQuestion(
            "Nephrology",
            "A 76-year-old with vomiting for 3 days has creatinine 2.4 (baseline 1.0). Urine sodium is 10 mEq/L and FENa is 0.4%. He takes no diuretics. What is the most likely cause?",
            listOf("Acute tubular necrosis", "Prerenal azotemia", "Acute interstitial nephritis", "Postrenal obstruction", "Rapidly progressive glomerulonephritis"),
            1,
            "FENa under 1% means the tubules are avidly holding sodium: prerenal (hypovolemia). ATN usually has FENa over 2% with muddy brown casts. On diuretics, use FEUrea (under 35% suggests prerenal).",
        ),
        MedQuestion(
            "Acid–Base",
            "A patient's ABG: pH 7.20, HCO₃⁻ 10 mEq/L, PaCO₂ 35 mmHg. Which best describes the acid–base status?",
            listOf("Metabolic acidosis with appropriate respiratory compensation", "Metabolic acidosis with concomitant respiratory acidosis", "Metabolic acidosis with concomitant respiratory alkalosis", "Primary respiratory acidosis", "Mixed metabolic acidosis and metabolic alkalosis"),
            1,
            "Winter's formula: expected PaCO₂ = 1.5 × 10 + 8 = 23 ± 2. The actual 35 is higher, so ventilation is inadequate: an added respiratory acidosis (e.g., fatigue, sedation).",
        ),
        MedQuestion(
            "Endocrinology",
            "A 44-year-old has hypertension on three drugs and a potassium of 3.1 mEq/L without diuretics. What is the best initial screening test?",
            listOf("24-hour urine metanephrines", "Plasma aldosterone-to-renin ratio", "Renal artery Doppler", "Overnight dexamethasone suppression test", "Adrenal CT"),
            1,
            "Resistant hypertension with spontaneous hypokalemia suggests primary aldosteronism: screen with the aldosterone-to-renin ratio, then confirm (e.g., saline suppression) before imaging.",
        ),
        MedQuestion(
            "Infectious Disease",
            "A 58-year-old has 5 watery stools a day after a course of clindamycin. C. difficile testing is positive. WBC 11,000, creatinine at baseline. It's the first episode. What is the best treatment?",
            listOf("Oral metronidazole", "IV vancomycin", "Oral vancomycin", "Fecal microbiota transplant", "Loperamide"),
            2,
            "Initial non-severe C. difficile: oral fidaxomicin or oral vancomycin. IV vancomycin doesn't reach the colon. Metronidazole is only an alternative if neither is available.",
        ),
        MedQuestion(
            "Cardiology",
            "A 35-year-old with no diabetes or ASCVD has an LDL of 215 mg/dL. Tendon xanthomas are seen on exam. What is the best treatment?",
            listOf("Lifestyle changes and recheck in 6 months", "Moderate-intensity statin", "High-intensity statin", "Ezetimibe alone", "Fish oil"),
            2,
            "LDL ≥ 190 mg/dL (likely familial hypercholesterolemia) warrants a high-intensity statin regardless of 10-year risk; add ezetimibe or a PCSK9 inhibitor if LDL stays high.",
        ),
        MedQuestion(
            "Pulmonology",
            "A 36-year-old Black woman has bilateral hilar lymphadenopathy, erythema nodosum and a calcium of 11.6. Biopsy shows non-caseating granulomas. What causes her hypercalcemia?",
            listOf("PTH-related peptide", "Increased 1-α-hydroxylase activity in macrophages", "Osteolytic bone lesions", "Primary hyperparathyroidism", "Vitamin D toxicity from supplements"),
            1,
            "Granulomas (sarcoidosis, TB) convert 25-OH vitamin D to calcitriol via macrophage 1-α-hydroxylase, independent of PTH. PTH is suppressed; steroids treat it.",
        ),
        MedQuestion(
            "Gastroenterology",
            "A woman with cirrhosis and ascites has abdominal pain and fever. Paracentesis shows 620 neutrophils/µL. Creatinine is 1.6. You start cefotaxime. What else improves her survival?",
            listOf("IV albumin on day 1 and day 3", "Large-volume paracentesis", "Furosemide", "Repeat paracentesis in 6 hours", "Oral norfloxacin"),
            0,
            "SBP with creatinine > 1, BUN > 30 or bilirubin > 4: IV albumin (1.5 g/kg day 1, 1 g/kg day 3) reduces hepatorenal syndrome and mortality.",
        ),
        MedQuestion(
            "Neurology",
            "A woman with myasthenia gravis has worsening weakness, difficulty swallowing and a weak cough during a UTI. SpO₂ is 97% on room air. Which is the best way to monitor for respiratory failure?",
            listOf("Pulse oximetry", "Serial ABGs", "Forced vital capacity and negative inspiratory force", "Chest X-ray", "Peak expiratory flow"),
            2,
            "In neuromuscular respiratory failure, oxygenation stays normal until late. Follow FVC and NIF; intubation is considered near FVC < 20 mL/kg or NIF weaker than −30 cmH₂O.",
        ),
        MedQuestion(
            "Endocrinology",
            "A 66-year-old with squamous-cell lung cancer is lethargic. Calcium is 14.8 mg/dL, PTH is low and he is dehydrated. What is the best first step?",
            listOf("IV zoledronic acid alone", "IV furosemide", "Aggressive IV isotonic saline", "Oral cinacalcet", "Hemodialysis"),
            2,
            "Severe hypercalcemia: start with IV saline (patients are volume-depleted), add calcitonin for a quick drop and a bisphosphonate for lasting control. Loop diuretics only after volume repletion, if overloaded.",
        ),
        MedQuestion(
            "Hematology",
            "A 59-year-old with rheumatoid arthritis has hemoglobin 9.8, MCV 82, ferritin 410 ng/mL, low serum iron and low TIBC. What is the most likely diagnosis?",
            listOf("Iron deficiency anemia", "Anemia of chronic inflammation", "Thalassemia trait", "Sideroblastic anemia", "Vitamin B12 deficiency"),
            1,
            "Anemia of inflammation: hepcidin traps iron, so ferritin is normal or high with low iron and low TIBC. Iron deficiency has low ferritin and high TIBC.",
        ),
        MedQuestion(
            "Infectious Disease",
            "A 32-year-old is newly diagnosed with HIV. CD4 count is 140 cells/µL. Toxoplasma IgG is negative. Which prophylaxis is indicated now?",
            listOf("Azithromycin", "Trimethoprim-sulfamethoxazole", "Fluconazole", "Valganciclovir", "None until viral load is known"),
            1,
            "CD4 < 200: TMP-SMX for Pneumocystis (it also covers Toxoplasma when CD4 < 100 and IgG is positive). MAC prophylaxis is no longer needed if ART starts promptly.",
        ),
        MedQuestion(
            "Infectious Disease",
            "A 27-year-old healthcare worker has a positive interferon-gamma release assay, no symptoms and a normal chest X-ray. What is the best management?",
            listOf("RIPE therapy for 6 months", "Repeat IGRA in 1 year", "Rifampin daily for 4 months", "Sputum AFB × 3, then observe", "No treatment"),
            2,
            "Latent TB: treat to prevent reactivation. Rifamycin-based short courses (4 months rifampin, or 3 months weekly isoniazid-rifapentine) are preferred for better completion and less hepatotoxicity.",
        ),
        MedQuestion(
            "Cardiology",
            "A patient on haloperidol and azithromycin has polymorphic VT with a twisting QRS axis. Pulses are present and BP is stable. QTc before the event was 590 ms. What is the best treatment?",
            listOf("IV amiodarone", "IV magnesium sulfate", "IV procainamide", "IV adenosine", "Oral metoprolol"),
            1,
            "Torsades de pointes: give IV magnesium (2 g), stop QT-prolonging drugs and correct K. Overdrive pacing or isoproterenol if it recurs; unstable patients need defibrillation.",
        ),
        MedQuestion(
            "Cardiology",
            "A 28-year-old has palpitations. ECG shows a regular narrow-complex tachycardia at 190/min. BP 118/76 and she is comfortable. A modified Valsalva maneuver fails. What is next?",
            listOf("Synchronized cardioversion", "IV adenosine 6 mg rapid push", "IV amiodarone", "IV digoxin", "Oral flecainide"),
            1,
            "Stable SVT: vagal maneuvers, then rapid IV adenosine (6 mg, then 12 mg). Cardioversion is for unstable patients.",
        ),
        MedQuestion(
            "Pulmonology",
            "A pleural effusion is tapped. Serum protein 7.0, LDH 200 (ULN 220). Which pleural fluid result makes it an exudate by Light's criteria?",
            listOf("Protein 3.0 g/dL", "LDH 110 U/L", "LDH 140 U/L", "Glucose 90 mg/dL", "pH 7.45"),
            2,
            "Exudate if any: fluid/serum protein > 0.5 (3.0/7.0 = 0.43, no), fluid/serum LDH > 0.6 (140/200 = 0.7, yes), or fluid LDH > ⅔ of the upper limit of normal (> 147: 140 is not).",
        ),
        MedQuestion(
            "Endocrinology",
            "A nurse has recurrent hypoglycemia. During an episode: glucose 38, insulin high, C-peptide very low, sulfonylurea screen negative. What is the most likely cause?",
            listOf("Insulinoma", "Sulfonylurea use", "Exogenous insulin", "Adrenal insufficiency", "Non-islet cell tumor"),
            2,
            "High insulin with suppressed C-peptide means insulin from outside. Insulinoma and sulfonylureas raise both insulin and C-peptide (the screen separates them).",
        ),
        MedQuestion(
            "Nephrology",
            "A 52-year-old has edema, 8 g/day of proteinuria, albumin 2.1 and a bland sediment. Serum anti-PLA2R antibodies are positive. What is the most likely diagnosis?",
            listOf("Minimal change disease", "FSGS", "Primary membranous nephropathy", "IgA nephropathy", "Diabetic nephropathy"),
            2,
            "Anti-phospholipase A2 receptor antibodies are found in most primary membranous nephropathy. It carries a high risk of renal vein thrombosis.",
        ),
        MedQuestion(
            "Hematology",
            "An 80-year-old on warfarin for AF has sudden headache and a right parietal intracerebral hemorrhage. INR is 3.4. What is the best reversal?",
            listOf("Hold warfarin only", "Oral vitamin K", "Fresh frozen plasma alone", "4-factor PCC plus IV vitamin K", "Protamine sulfate"),
            3,
            "Life-threatening bleeding on warfarin: 4-factor prothrombin complex concentrate (fast) plus IV vitamin K 10 mg (lasting). FFP is slower and needs large volumes.",
        ),
        MedQuestion(
            "Infectious Disease",
            "A 67-year-old has fever and blood cultures growing Streptococcus gallolyticus (formerly S. bovis). Echo shows a mitral vegetation. Besides antibiotics, what evaluation is needed?",
            listOf("Colonoscopy", "Dental panoramic X-ray", "CT of the head", "Bone marrow biopsy", "HIV test only"),
            0,
            "S. gallolyticus bacteremia or endocarditis is strongly linked to colorectal neoplasia: everyone needs a colonoscopy.",
        ),
        MedQuestion(
            "Gastroenterology",
            "A 59-year-old has gallstone pancreatitis. On day 1 he has fever 39.2°C, jaundice, bilirubin 6.1 and a dilated common bile duct. What is the best next step?",
            listOf("Cholecystectomy within 24 hours", "ERCP within 24 hours", "MRCP in 72 hours", "CT-guided drainage", "Supportive care only"),
            1,
            "Gallstone pancreatitis with cholangitis needs urgent ERCP. Without cholangitis or ongoing obstruction, early ERCP doesn't help; cholecystectomy is done during the same admission once mild pancreatitis settles.",
        ),
        MedQuestion(
            "Oncology",
            "A 45-year-old on chemotherapy for AML has fever 38.6°C. ANC is 200. BP is normal, no line infection or pneumonia is evident. Cultures are drawn. What is the best empiric therapy?",
            listOf("Oral ciprofloxacin and discharge", "IV cefepime", "IV vancomycin alone", "IV fluconazole", "Wait for culture results"),
            1,
            "Febrile neutropenia: within an hour, start an anti-pseudomonal β-lactam (cefepime, pip-tazo, meropenem). Add vancomycin only for specific reasons (unstable, line or skin infection, MRSA, pneumonia).",
        ),
        MedQuestion(
            "Neurology",
            "A 40-year-old has had a generalized tonic-clonic seizure for 6 minutes. IV access is in place and glucose is 112. What is the best first drug?",
            listOf("IV levetiracetam", "IV lorazepam", "IV phenytoin", "IV propofol", "Rectal valproate"),
            1,
            "Status epilepticus: a benzodiazepine first (IV lorazepam, or IM midazolam if no IV). Then a second-line drug (levetiracetam, fosphenytoin or valproate) if seizures continue.",
        ),
        MedQuestion(
            "Toxicology",
            "A 22-year-old took an unknown amount of acetaminophen 6 hours ago. She feels fine. Her 4-hour level was above the treatment line on the Rumack-Matthew nomogram. What is next?",
            listOf("Activated charcoal only", "N-acetylcysteine", "Hemodialysis", "Repeat level in 4 hours, then decide", "Fomepizole"),
            1,
            "Any level above the treatment line means N-acetylcysteine now. It works best within 8 hours of ingestion, so don't wait for repeat levels or liver tests.",
        ),
        MedQuestion(
            "Toxicology",
            "A 35-year-old took an overdose of amitriptyline. HR 128, BP 92/58, QRS 140 ms with a tall R wave in aVR. What is the best treatment?",
            listOf("IV physostigmine", "IV sodium bicarbonate", "IV flumazenil", "IV procainamide", "IV amiodarone"),
            1,
            "TCAs block cardiac sodium channels. QRS > 100 ms: IV sodium bicarbonate (sodium load plus alkalinization) to narrow the QRS and prevent arrhythmias. Physostigmine and flumazenil can cause seizures.",
        ),
        MedQuestion(
            "Cardiology",
            "A 78-year-old has exertional chest pain and syncope. There is a late-peaking systolic murmur at the right upper sternal border. Echo: aortic valve area 0.7 cm², mean gradient 48 mmHg. What is the best treatment?",
            listOf("Medical therapy with a beta blocker", "Aortic valve replacement (TAVR or surgical)", "Balloon valvuloplasty alone", "Repeat echo in 1 year", "Start a nitrate"),
            1,
            "Symptomatic severe aortic stenosis needs valve replacement; symptoms (angina, syncope, heart failure) mark a steep drop in survival. Choose TAVR vs surgery by age, risk and anatomy.",
        ),
        MedQuestion(
            "Endocrinology",
            "A man on prednisone 20 mg daily for a year has an appendectomy. Post-op he has BP 76/40 despite 3 L of fluids, with Na 129 and K 5.0. What is the best next step?",
            listOf("Start norepinephrine only", "IV hydrocortisone 100 mg", "ACTH stimulation test before any treatment", "Oral fludrocortisone", "IV levothyroxine"),
            1,
            "Adrenal crisis from suppressed HPA axis: give IV hydrocortisone right away (stress dose). Never delay treatment for testing; dexamethasone is used only if a stimulation test must be done first.",
        ),
        MedQuestion(
            "Gastroenterology",
            "A man with cirrhosis is confused and has asterixis after a few days of constipation. No GI bleeding or infection is found. What is the best first treatment?",
            listOf("Lactulose", "Rifaximin alone", "Protein-free diet", "Neomycin", "Flumazenil"),
            0,
            "Hepatic encephalopathy: treat the trigger and start lactulose, titrated to 2–3 soft stools a day. Rifaximin is added to prevent recurrence; protein restriction is no longer advised.",
        ),
        MedQuestion(
            "Pulmonology",
            "A 30-year-old asthmatic uses albuterol 4 days a week and wakes at night twice a month. She uses no controller. Which is the preferred change?",
            listOf("Montelukast", "Low-dose ICS-formoterol as needed (and daily)", "Oral prednisone burst", "Long-acting β-agonist alone", "Tiotropium alone"),
            1,
            "Uncontrolled asthma needs an inhaled corticosteroid. ICS-formoterol (as needed, or as maintenance-and-reliever) is preferred; LABA alone is never used in asthma.",
        ),
        MedQuestion(
            "Nephrology",
            "A 26-year-old has gross hematuria 2 days after a sore throat began. BP and creatinine are normal. Complement levels are normal. What is the most likely diagnosis?",
            listOf("Post-streptococcal glomerulonephritis", "IgA nephropathy", "Lupus nephritis", "Goodpasture syndrome", "Membranoproliferative GN"),
            1,
            "IgA nephropathy: hematuria within days of a URI (synpharyngitic) with normal complement. Post-strep GN comes 1–3 weeks after with low C3.",
        ),
        MedQuestion(
            "Rheumatology",
            "A 72-year-old woman has new headache, jaw pain when chewing and scalp tenderness. ESR is 92. She has no vision changes yet. What is the best next step?",
            listOf("Temporal artery biopsy, then steroids if positive", "Start high-dose prednisone now", "MRI of the brain", "NSAIDs and follow up", "Start methotrexate"),
            1,
            "Suspected giant cell arteritis: start high-dose glucocorticoids immediately to prevent blindness. Biopsy (or ultrasound) is still useful within 1–2 weeks of starting steroids.",
        ),
    )
}

/** Her answers: today's pick, a daily streak, and how many she got right. */
class MedQuestionStore(context: Context) {
    private val prefs = context.getSharedPreferences("med_qotd", Context.MODE_PRIVATE)

    data class Stats(val picked: Int?, val streak: Int, val correct: Int, val answered: Int)

    fun stats(today: LocalDate): Stats {
        val last = prefs.getString("last", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val streak = prefs.getInt("streak", 0)
        // The streak lives while she answered today or yesterday.
        val alive = last != null && !last.isBefore(today.minusDays(1))
        val picked = if (last == today) prefs.getInt("picked", -1).takeIf { it >= 0 } else null
        return Stats(picked, if (alive) streak else 0, prefs.getInt("correct", 0), prefs.getInt("answered", 0))
    }

    fun answer(today: LocalDate, pick: Int, correct: Boolean): Stats {
        val last = prefs.getString("last", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (last == today) return stats(today) // one answer a day
        val streak = if (last == today.minusDays(1)) prefs.getInt("streak", 0) + 1 else 1
        prefs.edit()
            .putString("last", today.toString())
            .putInt("picked", pick)
            .putInt("streak", streak)
            .putInt("answered", prefs.getInt("answered", 0) + 1)
            .putInt("correct", prefs.getInt("correct", 0) + if (correct) 1 else 0)
            .apply()
        return stats(today)
    }
}
