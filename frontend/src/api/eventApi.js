import apiClient from "./apiClient";

const eventApi = {
    getEvents: (params = {}) => {
        const query = {};
        if (params.keyword)   query.keyword   = params.keyword;
        if (params.location)  query.location  = params.location;
        if (params.dateFilter) query.dateFilter = params.dateFilter;
        if (params.eventType) query.eventType  = params.eventType;
        if (params.eventMode) query.eventMode  = params.eventMode;
        if (params.lat != null) query.lat = params.lat;
        if (params.lng != null) query.lng = params.lng;
        if (params.maxDistanceMiles) query.maxDistanceMiles = params.maxDistanceMiles;
        return apiClient.get("/events", { params: query });
    },

    getEventById: (id) => apiClient.get(`/events/${id}`),

    registerForEvent: (id) => apiClient.post(`/events/${id}/register`),

    cancelRegistration: (id) => apiClient.post(`/events/${id}/cancel-registration`),
};

export default eventApi;
