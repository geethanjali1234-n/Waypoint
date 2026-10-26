/**
 * Centralized option lists for the job seeker registration MultiSelect fields.
 * Kept as frontend constants for today's delivery - no backend endpoint or
 * MongoDB collection backs these yet.
 */

const SKILL_LABELS = [
  'Java',
  'JavaScript',
  'TypeScript',
  'Spring Boot',
  'Spring Security',
  'REST APIs',
  'Microservices',
  'React',
  'Angular',
  'Node.js',
  'HTML',
  'CSS',
  'MongoDB',
  'MySQL',
  'PostgreSQL',
  'Oracle',
  'SQL',
  'AWS',
  'Azure',
  'Docker',
  'Kubernetes',
  'Kafka',
  'Git',
  'GitHub',
  'Jenkins',
  'CI/CD',
  'JUnit',
  'Mockito',
  'JPA',
  'Hibernate',
  'Python',
  'C++',
  'C#',
  'Salesforce',
  'Redis',
  'GraphQL',
];

const PREFERRED_JOB_ROLE_LABELS = [
  'Java Developer',
  'Backend Developer',
  'Frontend Developer',
  'Full Stack Developer',
  'Java Full Stack Developer',
  'React Developer',
  'Angular Developer',
  'Node.js Developer',
  'Software Engineer',
  'Software Developer',
  'Web Developer',
  'API Developer',
  'Microservices Developer',
  'Cloud Engineer',
  'DevOps Engineer',
  'QA Engineer',
  'Automation Test Engineer',
  'Database Developer',
  'Data Engineer',
  'Business Analyst',
  'Project Coordinator',
];

export const SKILL_OPTIONS = SKILL_LABELS.map((label) => ({ label, value: label }));

export const PREFERRED_JOB_ROLE_OPTIONS = PREFERRED_JOB_ROLE_LABELS.map((label) => ({ label, value: label }));
