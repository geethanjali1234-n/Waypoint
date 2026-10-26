import apiClient from './apiClient';

/**
 * Organization Audit History (Company Admin only). All params are optional;
 * the backend scopes every result to the caller's organization.
 */
const myHrAuditApi = {
  history: (params = {}) => {
    const query = {};
    ['page', 'size', 'actionType', 'category', 'actorUserId', 'entityType', 'entityId', 'from', 'to', 'search']
      .forEach((key) => {
        const value = params[key];
        if (value !== undefined && value !== null && value !== '' && value !== 'ALL') {
          query[key] = value;
        }
      });
    return apiClient.get('/myhr/audit', { params: query });
  },
};

export default myHrAuditApi;
