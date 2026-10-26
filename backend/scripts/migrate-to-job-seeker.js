const database = db.getSiblingDB('waypoint');
const sourceCollection = 'employee_profiles';
const targetCollection = 'job_seeker_profiles';

function collectionExists(name) {
  return database.getCollectionInfos({ name }).length > 0;
}

function dropLegacyIndexes(collectionName) {
  const collection = database.getCollection(collectionName);
  for (const index of collection.getIndexes()) {
    const legacyKey = Object.keys(index.key).some((key) => /employee/i.test(key));
    if (index.name !== '_id_' && (legacyKey || /employee/i.test(index.name))) {
      collection.dropIndex(index.name);
    }
  }
}

function dropDifferentlyNamedIndex(collectionName, fieldName, expectedName) {
  const collection = database.getCollection(collectionName);
  for (const index of collection.getIndexes()) {
    const keys = Object.keys(index.key);
    if (keys.length === 1 && keys[0] === fieldName && index.name !== expectedName) {
      collection.dropIndex(index.name);
    }
  }
}

function assertNoConflictingProfileReferences(collectionName) {
  const conflicts = database.getCollection(collectionName).countDocuments({
    employeeProfileId: { $exists: true },
    jobSeekerProfileId: { $exists: true },
    $expr: { $ne: ['$employeeProfileId', '$jobSeekerProfileId'] },
  });
  if (conflicts > 0) {
    throw new Error(`${collectionName} has ${conflicts} conflicting profile reference(s)`);
  }
}

function replaceTerminology(collectionName, fieldName) {
  const collection = database.getCollection(collectionName);
  collection.updateMany(
    { [fieldName]: /employee/i },
    [{
      $set: {
        [fieldName]: {
          $replaceAll: {
            input: {
              $replaceAll: {
                input: `$${fieldName}`,
                find: 'Employee',
                replacement: 'Job_Seeker',
              },
            },
            find: 'employee',
            replacement: 'job_seeker',
          },
        },
      },
    }],
  );
}

const sourceExists = collectionExists(sourceCollection);
const targetExists = collectionExists(targetCollection);

if (sourceExists && targetExists) {
  const targetCount = database.getCollection(targetCollection).countDocuments();
  if (targetCount > 0) {
    throw new Error(`Both profile collections contain data; ${targetCollection} has ${targetCount} document(s)`);
  }
  database.getCollection(targetCollection).drop();
}

if (collectionExists(sourceCollection)) {
  database.getCollection(sourceCollection).renameCollection(targetCollection);
} else if (!collectionExists(targetCollection)) {
  throw new Error('Neither the legacy nor the job-seeker profile collection exists');
}

database.getCollection(targetCollection).updateMany(
  {},
  { $set: { _class: 'com.waypoint.entity.JobSeekerProfile' } },
);

const usersResult = database.users.updateMany(
  { role: 'EMPLOYEE' },
  { $set: { role: 'JOB_SEEKER' } },
);

for (const collectionName of ['job_applications', 'event_registrations']) {
  assertNoConflictingProfileReferences(collectionName);
  // Drop legacy unique indexes before renaming fields. Otherwise, after the
  // first document is renamed, multiple documents temporarily appear as the
  // same `{ parentId, employeeProfileId: null }` key and MongoDB correctly
  // rejects the update.
  dropLegacyIndexes(collectionName);
  database.getCollection(collectionName).updateMany(
    { employeeProfileId: { $exists: true } },
    { $rename: { employeeProfileId: 'jobSeekerProfileId' } },
  );
  dropDifferentlyNamedIndex(collectionName, 'jobSeekerProfileId', 'jobSeekerProfileId');
  database.getCollection(collectionName).createIndex(
    { jobSeekerProfileId: 1 },
    { name: 'jobSeekerProfileId' },
  );
}

database.job_applications.createIndex(
  { jobId: 1, jobSeekerProfileId: 1 },
  { name: 'jobId_jobSeekerProfileId_unique', unique: true },
);
database.event_registrations.createIndex(
  { eventId: 1, jobSeekerProfileId: 1 },
  { name: 'eventId_jobSeekerProfileId_unique', unique: true },
);

database.notifications.updateMany(
  { actionUrl: /^\/employee(?=\/|$)/ },
  [{ $set: { actionUrl: { $replaceOne: { input: '$actionUrl', find: '/employee', replacement: '/job-seeker' } } } }],
);

const oldSetupUserId = 'setup-employee-user';
const newSetupUserId = 'setup-job-seeker-user';
const oldSetupProfileId = 'setup-employee-profile';
const newSetupProfileId = 'setup-job-seeker-profile';

const oldSetupUser = database.users.findOne({ _id: oldSetupUserId });
if (oldSetupUser && !database.users.findOne({ _id: newSetupUserId })) {
  oldSetupUser._id = newSetupUserId;
  oldSetupUser.email = 'setup.job-seeker@waypoint.local';
  oldSetupUser.role = 'JOB_SEEKER';
  database.users.insertOne(oldSetupUser);
}

const profiles = database.getCollection(targetCollection);
const oldSetupProfile = profiles.findOne({ _id: oldSetupProfileId });
if (oldSetupProfile && !profiles.findOne({ _id: newSetupProfileId })) {
  oldSetupProfile._id = newSetupProfileId;
  oldSetupProfile.userId = newSetupUserId;
  oldSetupProfile.lastName = 'Job Seeker';
  oldSetupProfile._class = 'com.waypoint.entity.JobSeekerProfile';
  profiles.insertOne(oldSetupProfile);
}

database.job_applications.updateMany(
  { jobSeekerProfileId: oldSetupProfileId },
  { $set: { jobSeekerProfileId: newSetupProfileId } },
);
database.event_registrations.updateMany(
  { jobSeekerProfileId: oldSetupProfileId },
  { $set: { jobSeekerProfileId: newSetupProfileId } },
);
database.notifications.updateMany(
  { recipientUserId: oldSetupUserId },
  { $set: { recipientUserId: newSetupUserId } },
);
database.contact_support.updateMany(
  { userId: oldSetupUserId },
  { $set: { userId: newSetupUserId } },
);
profiles.deleteOne({ _id: oldSetupProfileId });
database.users.deleteOne({ _id: oldSetupUserId });

database.otp_verifications.updateMany(
  { email: 'setup.employee@waypoint.local' },
  { $set: { email: 'setup.job-seeker@waypoint.local' } },
);

replaceTerminology(targetCollection, 'resumePath');
replaceTerminology('job_applications', 'resumePath');
replaceTerminology('resumes.files', 'filename');
replaceTerminology('resumes.files', 'metadata.fileName');
replaceTerminology('resumes.files', 'metadata.legacyPath');

print(`Migrated ${usersResult.modifiedCount} legacy user role(s) to JOB_SEEKER.`);
print(`Profile documents: ${database.getCollection(targetCollection).countDocuments()}.`);
print(`Job applications: ${database.job_applications.countDocuments({ jobSeekerProfileId: { $exists: true } })}.`);
print(`Event registrations: ${database.event_registrations.countDocuments({ jobSeekerProfileId: { $exists: true } })}.`);
