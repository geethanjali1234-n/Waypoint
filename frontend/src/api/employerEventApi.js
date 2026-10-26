import apiClient from "./apiClient";

const employerEventApi = {
    listEmployerEvents: () => apiClient.get("/employer/events"),
    getEmployerEvent: (eventId) => apiClient.get(`/employer/events/${eventId}`),
    getDashboard: () => apiClient.get("/employer/events/dashboard"),
    getNewEventCode: () => apiClient.get("/employer/events/new-code"),
    createEvent: (payload) => apiClient.post("/employer/events", payload),
    updateEvent: (eventId, payload) => apiClient.put(`/employer/events/${eventId}`, payload),
    closeEvent: (eventId) => apiClient.patch(`/employer/events/${eventId}/close`),
    reopenEvent: (eventId) => apiClient.patch(`/employer/events/${eventId}/reopen`),
    getEventRegistrants: (eventId) => apiClient.get(`/employer/events/${eventId}/registrants`),
};

export default employerEventApi;
